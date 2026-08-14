package com.ruoyi.web.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 兼容性修复：项目启动时自动检测并修复已知的列名不一致问题。
 * 
 * 本类仅用于第一阶段迁移后的数据库修补，后续新环境使用最新的 panjia.sql 即可。
 */
@Component
public class PgCompatibilityPatchRunner implements CommandLineRunner
{
    private static final Logger log = LoggerFactory.getLogger(PgCompatibilityPatchRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public PgCompatibilityPatchRunner(JdbcTemplate jdbcTemplate)
    {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args)
    {
        renameColumnIfExists("sys_logininfor", "user_name", "login_name");
    }

    private void renameColumnIfExists(String tableName, String oldColumn, String newColumn)
    {
        try
        {
            String checkSql = "SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_name = ? AND column_name = ?";
            Integer cnt = jdbcTemplate.queryForObject(checkSql, Integer.class, tableName, oldColumn);
            if (cnt != null && cnt > 0)
            {
                String alterSql = String.format("ALTER TABLE %s RENAME COLUMN %s TO %s",
                        tableName, oldColumn, newColumn);
                jdbcTemplate.execute(alterSql);
                log.info("[PG兼容修复] {}: {} → {} 重命名成功", tableName, oldColumn, newColumn);
            }
            else
            {
                log.info("[PG兼容修复] {} 列 {} 已不存在，跳过", tableName, oldColumn);
            }
        }
        catch (Exception e)
        {
            log.warn("[PG兼容修复] 处理 {}.{} 失败：{}", tableName, oldColumn, e.getMessage());
        }
    }
}
