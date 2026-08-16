package com.panjia.framework.backup;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.druid.pool.DruidDataSource;
import com.panjia.common.config.PanJiaConfig;
import com.panjia.common.utils.StringUtils;
import com.panjia.common.utils.file.FileUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * 数据库备份恢复服务
 * <p>
 * 备份策略（优先顺序）：
 * 1. 若配置 {@code panjia.dbbackup.dockerContainer}（默认 panjia-postgres），
 *    则通过容器内 pg_dump/psql 命令完成；
 * 2. 否则依赖宿主机 PATH 上的 pg_dump/psql。
 * <p>
 * 产物：
 * - 备份：{backupDir}/panjia_backup_yyyyMMdd_HHmmss.zip
 *   zip 内包含：panjia.sql + MANIFEST.MF（数据库名/时间/版本）
 * - 恢复：解压 zip 取 panjia.sql 通过 psql 或 pg_restore 执行。
 */
@Service
public class DbBackupService
{
    private static final Logger log = LoggerFactory.getLogger(DbBackupService.class);

    private static final String SQL_NAME = "panjia.sql";
    private static final String MANIFEST_NAME = "MANIFEST.MF";
    private static final String PREFIX = "panjia_backup_";
    private static final String SUFFIX = ".zip";

    @Value("${panjia.dbbackup.backupDir:}")
    private String configuredBackupDir;

    @Value("${panjia.dbbackup.dockerContainer:panjia-postgres}")
    private String dockerContainer;

    /** 容器内 /tmp 下的临时中转目录名，避免与宿主机路径混淆 */
    @Value("${panjia.dbbackup.containerTmpDir:/tmp/panjia_db_backup}")
    private String containerTmpDir;

    /** 单个备份最大保留数（超出自动删除最旧），<=0 表示不限制 */
    @Value("${panjia.dbbackup.maxKeep:30}")
    private int maxKeep;

    /** 备份 zip 总目录，放在 profile/dbbackup 下 */
    private Path backupDir;

    @Resource(name = "dynamicDataSource")
    private javax.sql.DataSource dataSource;

    /** 从动态数据源中解析当前 master 的 URL / user / password / dbName */
    private DbConn conn;

    @PostConstruct
    public void init() throws IOException
    {
        this.backupDir = resolveBackupDir();
        Files.createDirectories(this.backupDir);
        this.conn = resolveConn();
        log.info("[DbBackup] backupDir={}, dockerContainer={}, db={}, user={}",
                backupDir, dockerContainer, conn.db, conn.user);
    }

    // ============================ 对外 API ============================

    /** 触发一次新备份，返回备份信息 map（含 fileName / size / createdAt 等） */
    public Map<String, Object> createBackup() throws Exception
    {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String fileName = PREFIX + ts + SUFFIX;
        Path zipPath = backupDir.resolve(fileName);
        Path workDir = Files.createTempDirectory("panjia-backup-");

        try
        {
            Path sqlFile = workDir.resolve(SQL_NAME);
            // 1. 导出 SQL
            runPgDump(sqlFile);
            // 2. 写 MANIFEST
            Path manifest = workDir.resolve(MANIFEST_NAME);
            writeManifest(manifest, fileName, sqlFile);
            // 3. 打包 zip
            zipFiles(zipPath, Arrays.asList(sqlFile.toFile(), manifest.toFile()));
            // 4. 裁剪保留数
            cleanupOldBackups();

            return toInfo(zipPath.toFile());
        }
        finally
        {
            deleteQuietly(workDir);
        }
    }

    /** 列出所有备份 */
    public List<Map<String, Object>> listBackups()
    {
        List<Map<String, Object>> list = new ArrayList<>();
        File[] files = backupDir.toFile().listFiles((d, n) -> n.startsWith(PREFIX) && n.endsWith(SUFFIX));
        if (files == null) return list;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File f : files) list.add(toInfo(f));
        return list;
    }

    public Map<String, Object> toInfo(File f)
    {
        Map<String, Object> m = new HashMap<>();
        m.put("fileName", f.getName());
        m.put("size", f.length());
        m.put("sizeText", formatFileSize(f.length()));
        m.put("createdAt", Date.from(Instant.ofEpochMilli(f.lastModified())));
        m.put("path", backupDir.relativize(f.toPath()).toString());
        return m;
    }

    private static String formatFileSize(long bytes)
    {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** 返回 zip 文件绝对路径 */
    public Path findBackupFile(String fileName)
    {
        if (StringUtils.isBlank(fileName) || !FileUtils.isValidFilename(fileName)) return null;
        Path p = backupDir.resolve(fileName).normalize();
        if (!p.startsWith(backupDir)) return null;
        return Files.isRegularFile(p) ? p : null;
    }

    public boolean deleteBackup(String fileName)
    {
        Path p = findBackupFile(fileName);
        return p != null && p.toFile().delete();
    }

    /** 从已存在的备份 zip 恢复数据库 */
    public String restoreFromBackup(String fileName) throws Exception
    {
        Path zip = findBackupFile(fileName);
        if (zip == null) throw new IllegalArgumentException("备份文件不存在：" + fileName);
        return doRestore(zip, false);
    }

    /** 从上传的 zip 恢复（先保存到 backupDir/upload_tmp，再解压恢复） */
    public String restoreFromUpload(MultipartFile mf) throws Exception
    {
        if (mf == null || mf.isEmpty()) throw new IllegalArgumentException("请选择备份 zip 文件");
        String orig = mf.getOriginalFilename();
        if (orig == null || !orig.toLowerCase().endsWith(".zip"))
            throw new IllegalArgumentException("仅支持 .zip 备份包");

        Files.createDirectories(backupDir);
        Path uploaded = backupDir.resolve("__upload_" + System.nanoTime() + ".zip");
        try (InputStream is = mf.getInputStream())
        {
            Files.copy(is, uploaded);
        }
        try
        {
            return doRestore(uploaded, true);
        }
        finally
        {
            uploaded.toFile().delete();
        }
    }

    public Path getBackupDir()
    {
        return backupDir;
    }

    // ============================ 恢复逻辑 ============================

    private String doRestore(Path zip, boolean uploaded) throws Exception
    {
        // 1. 校验 zip：必须包含 panjia.sql
        File sql = extractSqlFromZip(zip);
        try
        {
            return runPsql(sql);
        }
        finally
        {
            sql.delete();
        }
    }

    private File extractSqlFromZip(Path zip) throws IOException
    {
        File tmpDir = Files.createTempDirectory("panjia-restore-").toFile();
        boolean ok = false;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip.toFile())))
        {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null)
            {
                String name = e.getName();
                // 仅接受顶级条目 panjia.sql / MANIFEST.MF，忽略子目录
                if (name.contains("..") || name.startsWith("/") || name.startsWith(File.separator))
                    throw new IOException("非法 zip 条目：" + name);
                if (e.isDirectory()) continue;
                String base = new File(name).getName();
                if (SQL_NAME.equals(base) || MANIFEST_NAME.equals(base))
                {
                    File out = new File(tmpDir, base);
                    try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(out)))
                    {
                        byte[] buf = new byte[8192];
                        int c;
                        while ((c = zis.read(buf)) != -1) fos.write(buf, 0, c);
                    }
                }
            }
        }
        File sql = new File(tmpDir, SQL_NAME);
        if (!sql.exists()) throw new IOException("备份 zip 内缺少 " + SQL_NAME);
        ok = true;
        return sql;
    }

    // ============================ pg_dump / psql 执行 ============================

    private void runPgDump(Path sqlFile) throws Exception
    {
        // --clean --if-exists：在 CREATE TABLE 前生成 DROP TABLE IF EXISTS，恢复时可直接覆盖
        String opts = "--format=plain --no-owner --no-privileges --clean --if-exists";
        if (StringUtils.isNotBlank(dockerContainer))
        {
            String sqlName = sqlFile.getFileName().toString();
            String inside = containerTmpDir + "/" + sqlName;
            exec("docker", "exec", dockerContainer, "mkdir", "-p", containerTmpDir);
            exec("docker", "exec", "-i", dockerContainer,
                    "bash", "-c",
                    String.format("PGPASSWORD='%s' pg_dump -U %s -d %s %s > '%s'",
                            escape(conn.pass), conn.user, conn.db, opts, inside));
            exec("docker", "cp", dockerContainer + ":" + inside, sqlFile.toAbsolutePath().toString());
            exec("docker", "exec", dockerContainer, "rm", "-rf", inside);
            if (!sqlFile.toFile().exists() || sqlFile.toFile().length() == 0)
                throw new RuntimeException("pg_dump 失败或产物为空，请确认 Docker 容器 '" + dockerContainer + "' 内有 pg_dump");
            return;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("pg_dump");
        cmd.add("--host=" + conn.host);
        cmd.add("--port=" + conn.port);
        cmd.add("--username=" + conn.user);
        cmd.add("--dbname=" + conn.db);
        cmd.add("--format=plain");
        cmd.add("--no-owner");
        cmd.add("--no-privileges");
        cmd.add("--clean");
        cmd.add("--if-exists");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.put("PGPASSWORD", conn.pass);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (InputStream is = p.getInputStream();
                OutputStream fos = new BufferedOutputStream(new FileOutputStream(sqlFile.toFile())))
        {
            byte[] buf = new byte[8192];
            int c;
            while ((c = is.read(buf)) != -1) fos.write(buf, 0, c);
        }
        int rc = p.waitFor();
        if (rc != 0) throw new RuntimeException("pg_dump 退出码=" + rc);
    }

    private String runPsql(File sqlFile) throws Exception
    {
        StringBuilder logsb = new StringBuilder();
        // 恢复前置：清掉 public schema 并重建，确保干净环境（避免外键/已存在表阻塞）
        // 注意：字符串内不能包含单引号，避免 Docker bash -c 多层转义语法错误
        String preSql = "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;"
                + " GRANT ALL ON SCHEMA public TO " + conn.user + ";"
                + " GRANT ALL ON SCHEMA public TO public;";
        if (StringUtils.isNotBlank(dockerContainer))
        {
            String nano = String.valueOf(System.nanoTime());
            String inside = containerTmpDir + "/" + nano + "_" + SQL_NAME;
            String insidePre = containerTmpDir + "/" + nano + "_pre.sql";
            exec("docker", "exec", dockerContainer, "mkdir", "-p", containerTmpDir);
            // 写 pre sql 到容器内临时文件（避免命令行转义问题）
            exec("docker", "exec", "-i", dockerContainer, "bash", "-c",
                    "printf '%s\\n' '" + preSql.replace("'", "'\\''") + "' > '" + insidePre + "'");
            exec("docker", "cp", sqlFile.getAbsolutePath(), dockerContainer + ":" + inside);
            // 先执行前置清场，再执行 dump SQL（都设 ON_ERROR_STOP=1）
            String preScript = String.format(
                    "PGPASSWORD='%s' psql -U %s -d %s -v ON_ERROR_STOP=1 -f '%s'",
                    escape(conn.pass), conn.user, conn.db, insidePre);
            String preOut = exec("docker", "exec", "-i", dockerContainer, "bash", "-c", preScript);
            logsb.append(preOut);
            String script = String.format(
                    "PGPASSWORD='%s' psql -U %s -d %s -v ON_ERROR_STOP=1 -f '%s'",
                    escape(conn.pass), conn.user, conn.db, inside);
            String out = exec("docker", "exec", "-i", dockerContainer, "bash", "-c", script);
            logsb.append("\n").append(out);
            exec("docker", "exec", dockerContainer, "rm", "-rf", inside, insidePre);
            return logsb.length() > 3000 ? logsb.substring(0, 3000) : logsb.toString();
        }
        // 宿主机
        // 前置清场
        {
            List<String> cmd = new ArrayList<>();
            cmd.add("psql");
            cmd.add("--host=" + conn.host);
            cmd.add("--port=" + conn.port);
            cmd.add("--username=" + conn.user);
            cmd.add("--dbname=" + conn.db);
            cmd.add("-v"); cmd.add("ON_ERROR_STOP=1");
            cmd.add("-c"); cmd.add(preSql);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("PGPASSWORD", conn.pass);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = readAll(p.getInputStream());
            int rc = p.waitFor();
            logsb.append(out);
            if (rc != 0) throw new RuntimeException("psql-pre 清场失败 exit=" + rc + " " +
                    (out.length() > 1000 ? out.substring(0, 1000) : out));
        }
        // 执行 dump
        List<String> cmd = new ArrayList<>();
        cmd.add("psql");
        cmd.add("--host=" + conn.host);
        cmd.add("--port=" + conn.port);
        cmd.add("--username=" + conn.user);
        cmd.add("--dbname=" + conn.db);
        cmd.add("-v"); cmd.add("ON_ERROR_STOP=1");
        cmd.add("--file=" + sqlFile.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("PGPASSWORD", conn.pass);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = readAll(p.getInputStream());
        int rc = p.waitFor();
        logsb.append("\n").append(out);
        if (rc != 0) throw new RuntimeException("psql 恢复失败，退出码=" + rc + " 输出：" +
                (out.length() > 2000 ? out.substring(0, 2000) : out));
        return logsb.length() > 3000 ? logsb.substring(0, 3000) : logsb.toString();
    }

    private String exec(String... args) throws Exception
    {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = readAll(p.getInputStream());
        int rc = p.waitFor();
        if (rc != 0)
            throw new RuntimeException("命令失败 " + Arrays.toString(args) +
                    " exit=" + rc + " 输出：" +
                    (out.length() > 2000 ? out.substring(0, 2000) : out));
        return out;
    }

    private static String readAll(InputStream is) throws IOException
    {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int c;
        while ((c = is.read(buf)) != -1) sb.append(new String(buf, 0, c));
        return sb.toString();
    }

    // ============================ 工具 ============================

    private static void zipFiles(Path out, List<File> files) throws IOException
    {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out.toFile()))))
        {
            for (File f : files)
            {
                zos.putNextEntry(new ZipEntry(f.getName()));
                try (InputStream is = new FileInputStream(f))
                {
                    byte[] buf = new byte[8192];
                    int c;
                    while ((c = is.read(buf)) != -1) zos.write(buf, 0, c);
                }
                zos.closeEntry();
            }
        }
    }

    private void writeManifest(Path mf, String zipName, Path sqlFile) throws IOException
    {
        Properties p = new Properties();
        p.setProperty("BackupFileName", zipName);
        p.setProperty("CreatedAt", Instant.now().toString());
        p.setProperty("DatabaseProduct", "PostgreSQL");
        p.setProperty("DatabaseName", conn.db);
        p.setProperty("AppName", "PanJia");
        p.setProperty("Version", StringUtils.isEmpty(PanJiaConfig.getVersion()) ? "0.1.0" : PanJiaConfig.getVersion());
        p.setProperty("SqlFile", SQL_NAME);
        p.setProperty("SqlSizeBytes", String.valueOf(Files.size(sqlFile)));
        try (OutputStream os = new FileOutputStream(mf.toFile()))
        {
            p.store(os, "PanJia DB Backup Manifest");
        }
    }

    private void cleanupOldBackups()
    {
        if (maxKeep <= 0) return;
        File[] files = backupDir.toFile().listFiles((d, n) -> n.startsWith(PREFIX) && n.endsWith(SUFFIX));
        if (files == null || files.length <= maxKeep) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int toDel = files.length - maxKeep;
        for (int i = 0; i < toDel; i++)
        {
            boolean ok = files[i].delete();
            log.info("[DbBackup] 清理过期备份 {} -> {}", files[i].getName(), ok);
        }
    }

    private static void deleteQuietly(Path p)
    {
        try
        {
            Files.walk(p).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
        catch (Exception ignore) {}
    }

    private static String escape(String s)
    {
        // bash 单引号字符串内：单引号 -> '\\''
        if (s == null) return "";
        return s.replace("'", "'\\''");
    }

    private Path resolveBackupDir()
    {
        String dir;
        if (StringUtils.isNotBlank(configuredBackupDir))
        {
            dir = configuredBackupDir;
        }
        else
        {
            String profile = PanJiaConfig.getProfile();
            if (StringUtils.isBlank(profile))
            {
                // Fallback: user.dir 下的 dbbackup（避免 profile 为 null 时拼成 "null/dbbackup"）
                profile = System.getProperty("user.dir");
                log.warn("[DbBackup] panjia.profile 未配置，使用 user.dir 作为 fallback：{}", profile);
            }
            dir = profile + "/dbbackup";
        }
        return Paths.get(dir).toAbsolutePath().normalize();
    }

    /** 从 DruidDataSource / JDBC URL / user / pass 中解析连接信息 */
    private DbConn resolveConn()
    {
        javax.sql.DataSource master = dataSource;
        if (dataSource instanceof com.panjia.framework.datasource.DynamicDataSource dyn)
        {
            java.lang.reflect.Field f1 = findField(dyn.getClass(), "resolvedDefaultDataSource");
            if (f1 != null)
            {
                try { f1.setAccessible(true); Object v = f1.get(dyn); if (v instanceof javax.sql.DataSource) master = (javax.sql.DataSource) v; }
                catch (Exception ignore) {}
            }
            if (master == dataSource)
            {
                java.lang.reflect.Field f2 = findField(dyn.getClass(), "defaultTargetDataSource");
                if (f2 != null)
                {
                    try { f2.setAccessible(true); Object v = f2.get(dyn); if (v instanceof javax.sql.DataSource) master = (javax.sql.DataSource) v; }
                    catch (Exception ignore) {}
                }
            }
        }
        DruidDataSource ds = (master instanceof DruidDataSource d) ? d : null;
        DbConn c = new DbConn();
        if (ds != null)
        {
            String url = ds.getUrl();
            c.user = ds.getUsername();
            c.pass = ds.getPassword() == null ? "" : ds.getPassword();
            parseJdbcUrl(url, c);
        }
        return c;
    }

    private static java.lang.reflect.Field findField(Class<?> clz, String name)
    {
        for (Class<?> c = clz; c != null && c != Object.class; c = c.getSuperclass())
        {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignore) {}
        }
        return null;
    }

    /** jdbc:postgresql://host:port/dbname?opt... */
    private static void parseJdbcUrl(String url, DbConn out)
    {
        String s = url;
        if (s.startsWith("jdbc:postgresql:")) s = s.substring("jdbc:postgresql:".length());
        if (s.startsWith("//"))
        {
            s = s.substring(2);
            int slash = s.indexOf('/');
            if (slash < 0) return;
            String hostPort = s.substring(0, slash);
            String rest = s.substring(slash + 1);
            String db = rest;
            int q = db.indexOf('?');
            if (q >= 0) db = db.substring(0, q);
            out.db = db;
            int colon = hostPort.indexOf(':');
            if (colon < 0)
            {
                out.host = hostPort;
                out.port = "5432";
            }
            else
            {
                out.host = hostPort.substring(0, colon);
                out.port = hostPort.substring(colon + 1);
            }
        }
        else
        {
            // 简化 jdbc:postgresql:dbname
            int q = s.indexOf('?');
            out.db = q >= 0 ? s.substring(0, q) : s;
            out.host = "localhost";
            out.port = "5432";
        }
    }

    static class DbConn
    {
        String host = "localhost";
        String port = "5432";
        String db   = "panjia";
        String user = "panjia";
        String pass = "";
    }
}
