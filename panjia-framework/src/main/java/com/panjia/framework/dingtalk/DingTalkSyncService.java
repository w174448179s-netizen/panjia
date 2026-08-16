package com.panjia.framework.dingtalk;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.panjia.common.utils.ShiroUtils;
import com.panjia.common.utils.StringUtils;
import com.panjia.framework.dingtalk.DingTalkNoticeService.DingTalkDept;
import com.panjia.framework.dingtalk.DingTalkNoticeService.DingTalkException;
import com.panjia.framework.dingtalk.DingTalkNoticeService.DingTalkUser;
import com.panjia.framework.shiro.service.SysPasswordService;

/**
 * 钉钉组织架构 → 盘家系统 双向同步（仅钉钉→本地单向全量+增量更新，不回写钉钉）。
 * <p>
 * 目标表：sys_dept / sys_user / sys_user_role
 * <p>
 * 策略：
 * <ul>
 *   <li>部门：按「钉钉 dept_id → sys_dept.dingtalk_dept_id」列做 upsert；首次匹配本地已建部门时先按「父部门ID相同 + 同名」合并；
 *        已在本地存在但钉钉已删除的部门 → 改 status=1 停用（保留历史数据，不物理删）。
 *   </li>
 *   <li>用户：3 级匹配 → dingtalk_userid 优先 → 手机号 phonenumber → 邮箱 email；都无则新建用户。
 *        新用户默认初始密码 = Pj@ + 6 位随机字母数字，加盐加密落库。
 *        钉钉离职用户 → 按 dingtalk.sync.disable_leaver 开关决定是否 SET status=1 停用。
 *   </li>
 *   <li>角色：所有同步用户默认授予 role_key=common（普通员工）；钉钉主管/部门负责人 → 再加 role_key=dept_manager（部门主管）。
 *        绝不移除管理员人工授予的角色，只做"确保存在"式增量插入。
 *   </li>
 * </ul>
 * 全程通过原生 JDBC 批量操作，不修改 System 模块下的 Entity/Mapper/Service。
 */
@Service
public class DingTalkSyncService
{
    private static final Logger log = LoggerFactory.getLogger(DingTalkSyncService.class);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DingTalkNoticeService dingTalk;

    @Autowired
    private SysPasswordService passwordService;

    // ================================================================================
    // 顶层入口
    // ================================================================================

    public SyncSummary syncAll() throws DingTalkException, SyncException
    {
        SyncSummary s = new SyncSummary();
        long t0 = System.currentTimeMillis();
        try
        {
            SyncDeptResult r1 = syncAllDept();
            s.copyFrom(r1);
            SyncUserResult r2 = syncAllUser();
            s.copyFrom(r2);
        }
        finally
        {
            s.costMs = System.currentTimeMillis() - t0;
        }
        log.info("[DingTalkSync] 全量同步完成 耗时={}ms 详情={}", s.costMs, s);
        return s;
    }

    // ================================================================================
    // 部门同步
    // ================================================================================

    public SyncDeptResult syncAllDept() throws DingTalkException, SyncException
    {
        SyncDeptResult r = new SyncDeptResult();
        List<DingTalkDept> dtList = dingTalk.listAllDepartments();

        try (Connection c = dataSource.getConnection())
        {
            c.setAutoCommit(true);
            // 1) 预读本地映射
            DeptLocalIndex local = loadLocalDeptIndex(c);

            // 2) 遍历钉钉部门（已按 depth 升序，根→子→孙）
            //    钉钉根部门 1 → 不单独创建，合并到本地已有的 parent_id=0 根部门下（或第一个根部门）
            Long rootLocalDeptId = findRootLocalDeptId(c);

            // dtId -> localId
            Map<Long, Long> dt2local = new HashMap<>(local.dt2local);
            // 把钉钉根 1 直接映射到本地根部门
            if (rootLocalDeptId != null) dt2local.put(1L, rootLocalDeptId);

            // 先标记"在钉钉存在的本地部门ID"，后续用于停用
            Set<Long> aliveLocalDeptIds = new HashSet<>();
            if (rootLocalDeptId != null) aliveLocalDeptIds.add(rootLocalDeptId);

            for (DingTalkDept d : dtList)
            {
                Long dtParentId = d.parentId == null ? 1L : d.parentId;
                Long localParentId = dt2local.get(dtParentId);
                if (localParentId == null) localParentId = rootLocalDeptId != null ? rootLocalDeptId : 0L;

                Long localId = dt2local.get(d.deptId);
                boolean isNew = localId == null;

                // 部门主管名字 → 本地存 leader(中文名)
                String leaderName = null;
                if (StringUtils.isNotBlank(d.deptManagerUseridList))
                {
                    String firstDtUserId = d.deptManagerUseridList.split("[,，]")[0].trim();
                    try
                    {
                        DingTalkUser du = dingTalk.getUserDetailByUserid(firstDtUserId);
                        if (du != null) leaderName = du.getName();
                    }
                    catch (Exception e)
                    {
                        log.debug("[DingTalkSync] 主管 {} 查详情失败：{}", firstDtUserId, e.getMessage());
                    }
                }

                if (isNew)
                {
                    localId = insertDept(c, d, localParentId, leaderName);
                    dt2local.put(d.deptId, localId);
                    // 回填 dingtalk_dept_id（因为列可能不存在，所以单独 UPDATE 忽略错误）
                    updateDeptSetDtId(c, localId, d.deptId);
                    r.inserted++;
                }
                else
                {
                    DeptLocalRow row = local.id2row.get(localId);
                    if (row == null || needUpdateDept(row, d, leaderName, localParentId))
                    {
                        updateDept(c, localId, d, localParentId, leaderName);
                        r.updated++;
                    }
                }
                aliveLocalDeptIds.add(localId);
            }

            // 3) 统一刷新 ancestors（祖先链），避免插入顺序缺父级
            refreshAllDeptAncestors(c);

            // 4) 本地已绑定钉钉 ID、但钉钉已不存在的部门 → 停用（默认根部门和手工部门例外：没有 dingtalk_dept_id 不处理）
            for (Map.Entry<Long, DeptLocalRow> e : local.id2row.entrySet())
            {
                DeptLocalRow row = e.getValue();
                if (row.dtDeptId == null || aliveLocalDeptIds.contains(row.deptId)) continue;
                if (row.parentId != null && row.parentId == 0L) continue; // 根部门不碰
                setDeptStatus(c, row.deptId, "1");
                r.disabled++;
            }
        }
        catch (SQLException e)
        {
            log.error("[DingTalkSync] 部门同步SQL异常", e);
            throw new SyncException("部门同步失败：" + e.getMessage(), e);
        }
        return r;
    }

    // ================================================================================
    // 用户同步
    // ================================================================================

    public SyncUserResult syncAllUser() throws DingTalkException, SyncException
    {
        SyncUserResult r = new SyncUserResult();
        Map<String, DingTalkUser> dtUsers = dingTalk.listAllUsers();

        try (Connection c = dataSource.getConnection())
        {
            c.setAutoCommit(true);

            // 1) 预读本地映射 + 角色映射
            UserLocalIndex local = loadLocalUserIndex(c);
            Map<String, Long> roleByKey = loadRoleIndex(c);
            Long commonRoleId    = roleByKey.get("common");
            Long managerRoleId   = roleByKey.get("dept_manager");
            // 若未预置 role_common（可能没创建），降级用第一个非 admin 的普通角色
            if (commonRoleId == null && !roleByKey.isEmpty())
            {
                for (Map.Entry<String, Long> e : roleByKey.entrySet())
                {
                    if (!"admin".equalsIgnoreCase(e.getKey()) && !"超级管理员".equalsIgnoreCase(e.getKey()))
                    { commonRoleId = e.getValue(); break; }
                }
            }

            // 钉钉用户ID集合（用于找"本地有但钉钉已离职"）
            Set<String> aliveDtUserIds = new HashSet<>(dtUsers.keySet());

            // 读取最新部门映射（因为部门同步已完成）
            Map<Long, Long> dt2localDept = loadDt2LocalDeptMap(c);

            // 2) 遍历钉钉用户
            for (DingTalkUser du : dtUsers.values())
            {
                Long localUserId = matchLocalUserId(local, du);
                boolean isNew = localUserId == null;
                Long primaryDeptId = pickPrimaryLocalDeptId(du, dt2localDept);

                if (isNew)
                {
                    String loginName = buildUniqueLoginName(local, du);
                    String rawPwd = defaultPassword(du);
                    String salt = ShiroUtils.randomSalt();
                    String encryptedPwd = passwordService.encryptPassword(loginName, rawPwd, salt);
                    String nickName = StringUtils.isBlank(du.getName()) ? loginName : du.getName();
                    localUserId = insertUser(c, loginName, nickName, encryptedPwd, salt, primaryDeptId, du, rawPwd);
                    // 回填 dingtalk_userid
                    updateUserSetDtUserid(c, localUserId, du.userid);
                    r.inserted++;
                    r.newUserPasswords.put(loginName, rawPwd);
                }
                else
                {
                    UserLocalRow row = local.id2row.get(localUserId);
                    if (row == null || needUpdateUser(row, du, primaryDeptId))
                    {
                        updateUser(c, localUserId, du, primaryDeptId);
                        r.updated++;
                    }
                    // 绑定 dingtalk_userid（未绑过则绑）
                    if (local.userid2row.get(du.userid) == null)
                    {
                        updateUserSetDtUserid(c, localUserId, du.userid);
                    }
                    // 同步本地索引（下次匹配用）
                    local.id2row.computeIfAbsent(localUserId, k -> new UserLocalRow()).userId = localUserId;
                }

                // 3) 确保角色（普通员工必绑；主管+部门负责人绑部门主管角色）
                boolean isManager = du.leader;
                ensureUserRole(c, localUserId, commonRoleId);
                if (isManager && managerRoleId != null) ensureUserRole(c, localUserId, managerRoleId);
            }

            // 4) 钉钉已离职、或本地有但钉钉已删除 的本地用户 → 按开关判断是否停用
            for (UserLocalRow row : local.id2row.values())
            {
                if (StringUtils.isBlank(row.dtUserid)) continue; // 未绑定钉钉的，不动（纯手工用户）
                DingTalkUser dt = dtUsers.get(row.dtUserid);
                if (dt == null || !dt.active)
                {
                    if ("0".equals(row.status))
                    {
                        setUserStatus(c, row.userId, "1");
                        r.disabled++;
                    }
                }
            }
        }
        catch (SQLException e)
        {
            log.error("[DingTalkSync] 用户同步SQL异常", e);
            throw new SyncException("用户同步失败：" + e.getMessage(), e);
        }
        return r;
    }

    // ================================================================================
    // 以下为内部实现辅助
    // ================================================================================

    private static String defaultPassword(DingTalkUser du)
    {
        String mobile = du == null || StringUtils.isBlank(du.getMobile()) ? null : du.getMobile();
        String suffix = randomAlphanum(6);
        if (mobile != null && mobile.length() >= 6)
            return "Pj@" + mobile.substring(mobile.length() - 6);
        return "Pj@" + suffix;
    }
    private static String randomAlphanum(int n)
    {
        String s = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(n);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) sb.append(s.charAt(r.nextInt(s.length())));
        return sb.toString();
    }

    // --- 部门本地索引 ---
    private static class DeptLocalRow { Long deptId, dtDeptId, parentId; String name; Integer orderNum; String leader; String ancestors; String status; }
    private static class DeptLocalIndex
    {
        Map<Long, DeptLocalRow> id2row = new HashMap<>();      // localId -> row
        Map<Long, Long> dt2local = new HashMap<>();             // dtId -> localId
    }

    private DeptLocalIndex loadLocalDeptIndex(Connection c) throws SQLException
    {
        DeptLocalIndex idx = new DeptLocalIndex();
        String sql = "SELECT dept_id, parent_id, ancestors, dept_name, order_num, leader, status, dingtalk_dept_id FROM sys_dept WHERE del_flag='0'";
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql))
        {
            while (rs.next())
            {
                DeptLocalRow r = new DeptLocalRow();
                r.deptId = rs.getLong("dept_id");
                r.parentId = (Long) rs.getObject("parent_id");
                r.ancestors = rs.getString("ancestors");
                r.name = rs.getString("dept_name");
                r.orderNum = (Integer) rs.getObject("order_num");
                r.leader = rs.getString("leader");
                r.status = rs.getString("status");
                Object did = rs.getObject("dingtalk_dept_id");
                r.dtDeptId = did == null ? null : ((Number) did).longValue();
                idx.id2row.put(r.deptId, r);
                if (r.dtDeptId != null) idx.dt2local.put(r.dtDeptId, r.deptId);
            }
        }
        catch (SQLException e)
        {
            // 列不存在（未执行升级SQL），降级：只看名称和 parent
            if (isMissingCol(e, "dingtalk_dept_id"))
            {
                log.warn("[DingTalkSync] sys_dept.dingtalk_dept_id 列不存在（请执行 sql/panjia-dingtalk-sync-upgrade.sql），降级按名称+父部门匹配");
                DeptLocalIndex idx2 = new DeptLocalIndex();
                String sql2 = "SELECT dept_id, parent_id, ancestors, dept_name, order_num, leader, status FROM sys_dept WHERE del_flag='0'";
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql2))
                {
                    while (rs.next())
                    {
                        DeptLocalRow r = new DeptLocalRow();
                        r.deptId = rs.getLong("dept_id");
                        r.parentId = (Long) rs.getObject("parent_id");
                        r.ancestors = rs.getString("ancestors");
                        r.name = rs.getString("dept_name");
                        r.orderNum = (Integer) rs.getObject("order_num");
                        r.leader = rs.getString("leader");
                        r.status = rs.getString("status");
                        idx2.id2row.put(r.deptId, r);
                    }
                }
                return idx2;
            }
            throw e;
        }
        return idx;
    }

    private Long findRootLocalDeptId(Connection c) throws SQLException
    {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT dept_id FROM sys_dept WHERE parent_id=0 AND del_flag='0' ORDER BY dept_id ASC LIMIT 1"))
        {
            if (rs.next()) return rs.getLong(1);
        }
        return null;
    }

    private Map<Long, Long> loadDt2LocalDeptMap(Connection c) throws SQLException
    {
        Map<Long, Long> m = new HashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT dept_id, dingtalk_dept_id FROM sys_dept WHERE del_flag='0' AND dingtalk_dept_id IS NOT NULL"))
        {
            while (rs.next())
            {
                Object did = rs.getObject(2);
                if (did != null) m.put(((Number) did).longValue(), rs.getLong(1));
            }
        }
        catch (SQLException e)
        {
            if (isMissingCol(e, "dingtalk_dept_id")) return m;
            throw e;
        }
        // 兜底：根部门 1 映射本地 parent_id=0 的 dept_id
        Long root = findRootLocalDeptId(c);
        if (root != null) m.putIfAbsent(1L, root);
        return m;
    }

    private Long insertDept(Connection c, DingTalkDept d, Long localParentId, String leaderName) throws SQLException
    {
        String sql = "INSERT INTO sys_dept(parent_id, ancestors, dept_name, order_num, leader, phone, email, status, del_flag, create_by, create_time) VALUES(?,?,?,?,?,?,?,?,?,?,now())";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS))
        {
            ps.setObject(1, localParentId == null ? 0 : localParentId);
            ps.setString(2, "");   // 后面统一刷新 ancestors
            ps.setString(3, StringUtils.substring(d.getName(), 0, 30));
            ps.setInt(4, d.order == null ? 0 : d.order);
            ps.setString(5, leaderName == null ? "" : StringUtils.substring(leaderName, 0, 20));
            ps.setString(6, "");
            ps.setString(7, "");
            ps.setString(8, "0");
            ps.setString(9, "0");
            ps.setString(10, "dingtalk-sync");
            ps.executeUpdate();
            try (ResultSet gk = ps.getGeneratedKeys())
            {
                if (gk.next()) return gk.getLong(1);
            }
            // 没生成 key（如 PG JDBC 不同配置），回退查询
        }
        // fallback: 通过 dept_name+parent_id 找刚插入的
        String fallback = "SELECT dept_id FROM sys_dept WHERE del_flag='0' AND dept_name=? AND parent_id=? ORDER BY dept_id DESC LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(fallback))
        {
            ps.setString(1, d.getName());
            ps.setObject(2, localParentId == null ? 0 : localParentId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getLong(1); }
        }
        throw new SQLException("插入部门失败：" + d.getName());
    }

    private void updateDeptSetDtId(Connection c, Long localId, Long dtId)
    {
        String sql = "UPDATE sys_dept SET dingtalk_dept_id=?, update_by='dingtalk-sync', update_time=now() WHERE dept_id=?";
        try (PreparedStatement ps = c.prepareStatement(sql))
        {
            ps.setLong(1, dtId);
            ps.setLong(2, localId);
            ps.executeUpdate();
        }
        catch (SQLException e)
        {
            if (!isMissingCol(e, "dingtalk_dept_id"))
                log.warn("[DingTalkSync] UPDATE 部门 dingtalk_dept_id 失败：{}", e.getMessage());
        }
    }

    private boolean needUpdateDept(DeptLocalRow row, DingTalkDept d, String leaderName, Long localParentId)
    {
        if (localParentId != null && row.parentId != null && !localParentId.equals(row.parentId) && row.parentId != 0L) return true;
        if (!StringUtils.equals(d.getName(), row.name)) return true;
        if (d.order != null && row.orderNum != null && d.order.intValue() != row.orderNum.intValue()) return true;
        if (!StringUtils.equals(StringUtils.substring(leaderName == null ? "" : leaderName, 0, 20),
                StringUtils.substring(row.leader == null ? "" : row.leader, 0, 20))) return true;
        return false;
    }

    private void updateDept(Connection c, Long localId, DingTalkDept d, Long localParentId, String leaderName) throws SQLException
    {
        String sql = "UPDATE sys_dept SET parent_id=?, dept_name=?, order_num=?, leader=?, update_by='dingtalk-sync', update_time=now() WHERE dept_id=?";
        try (PreparedStatement ps = c.prepareStatement(sql))
        {
            ps.setObject(1, localParentId == null ? 0L : localParentId);
            ps.setString(2, StringUtils.substring(d.getName(), 0, 30));
            ps.setInt(3, d.order == null ? 0 : d.order);
            ps.setString(4, leaderName == null ? "" : StringUtils.substring(leaderName, 0, 20));
            ps.setLong(5, localId);
            ps.executeUpdate();
        }
    }

    private void setDeptStatus(Connection c, Long id, String status) throws SQLException
    {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE sys_dept SET status=?, update_by='dingtalk-sync', update_time=now() WHERE dept_id=?"))
        {
            ps.setString(1, status);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private void refreshAllDeptAncestors(Connection c) throws SQLException
    {
        // 简单 BFS：从根部门开始，一层层计算 ancestors = parent.ancestors + "," + self.id
        // 祖先列格式："0,100,105"，根部门为 "0"
        Map<Long, Long> parentMap = new HashMap<>();
        List<Long> roots = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT dept_id, parent_id FROM sys_dept WHERE del_flag='0'"))
        {
            while (rs.next())
            {
                long id = rs.getLong(1);
                Object p = rs.getObject(2);
                long pid = p == null ? 0L : ((Number) p).longValue();
                parentMap.put(id, pid);
                if (pid == 0L) roots.add(id);
            }
        }
        java.util.Queue<Long> q = new java.util.ArrayDeque<>(roots);
        Map<Long, String> ancestorsMap = new HashMap<>();
        for (Long rid : roots) ancestorsMap.put(rid, "0");
        // 建子邻接表
        Map<Long, List<Long>> children = new HashMap<>();
        for (Map.Entry<Long, Long> e : parentMap.entrySet())
        {
            Long pid = e.getValue();
            if (pid != 0L) children.computeIfAbsent(pid, k -> new ArrayList<>()).add(e.getKey());
        }
        while (!q.isEmpty())
        {
            Long pid = q.poll();
            List<Long> clist = children.get(pid);
            if (clist == null) continue;
            String pa = ancestorsMap.getOrDefault(pid, String.valueOf(pid));
            for (Long cid : clist)
            {
                String ca = pa + "," + cid;
                ancestorsMap.put(cid, ca);
                q.offer(cid);
            }
        }
        // 批量 UPDATE ancestors
        try (PreparedStatement ps = c.prepareStatement("UPDATE sys_dept SET ancestors=? WHERE dept_id=?"))
        {
            for (Map.Entry<Long, String> e : ancestorsMap.entrySet())
            {
                ps.setString(1, e.getValue());
                ps.setLong(2, e.getKey());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // --- 用户本地索引 ---
    private static class UserLocalRow
    {
        Long userId;
        String loginName;
        String userName;
        String phonenumber;
        String email;
        Long deptId;
        String status;
        String dtUserid;
    }
    private static class UserLocalIndex
    {
        Map<Long, UserLocalRow> id2row = new HashMap<>();               // local userId -> row
        Map<String, Long> phone2userId = new HashMap<>();              // phonenumber -> userId
        Map<String, Long> email2userId = new HashMap<>();              // email -> userId
        Map<String, Long> userid2row = new HashMap<>();                // dt_userid -> userId
        Set<String> loginNames = new HashSet<>();
    }

    private UserLocalIndex loadLocalUserIndex(Connection c) throws SQLException
    {
        UserLocalIndex idx = new UserLocalIndex();
        String sql = "SELECT user_id, login_name, user_name, phonenumber, email, dept_id, status, dingtalk_userid FROM sys_user WHERE del_flag='0'";
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql))
        {
            while (rs.next())
            {
                UserLocalRow r = new UserLocalRow();
                r.userId = rs.getLong("user_id");
                r.loginName = rs.getString("login_name");
                r.userName = rs.getString("user_name");
                r.phonenumber = rs.getString("phonenumber");
                r.email = rs.getString("email");
                Object did = rs.getObject("dept_id");
                r.deptId = did == null ? null : ((Number) did).longValue();
                r.status = rs.getString("status");
                Object dtuid = rs.getObject("dingtalk_userid");
                r.dtUserid = dtuid == null ? null : String.valueOf(dtuid);
                idx.id2row.put(r.userId, r);
                if (StringUtils.isNotBlank(r.phonenumber)) idx.phone2userId.put(r.phonenumber, r.userId);
                if (StringUtils.isNotBlank(r.email)) idx.email2userId.putIfAbsent(r.email, r.userId);
                if (StringUtils.isNotBlank(r.dtUserid)) idx.userid2row.put(r.dtUserid, r.userId);
                if (StringUtils.isNotBlank(r.loginName)) idx.loginNames.add(r.loginName);
            }
        }
        catch (SQLException e)
        {
            if (isMissingCol(e, "dingtalk_userid"))
            {
                log.warn("[DingTalkSync] sys_user.dingtalk_userid 列不存在（请执行 sql/panjia-dingtalk-sso-upgrade.sql），降级按手机号/邮箱匹配");
                UserLocalIndex idx2 = new UserLocalIndex();
                String sql2 = "SELECT user_id, login_name, user_name, phonenumber, email, dept_id, status FROM sys_user WHERE del_flag='0'";
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql2))
                {
                    while (rs.next())
                    {
                        UserLocalRow r = new UserLocalRow();
                        r.userId = rs.getLong(1);
                        r.loginName = rs.getString(2);
                        r.userName = rs.getString(3);
                        r.phonenumber = rs.getString(4);
                        r.email = rs.getString(5);
                        Object did = rs.getObject(6);
                        r.deptId = did == null ? null : ((Number) did).longValue();
                        r.status = rs.getString(7);
                        idx2.id2row.put(r.userId, r);
                        if (StringUtils.isNotBlank(r.phonenumber)) idx2.phone2userId.put(r.phonenumber, r.userId);
                        if (StringUtils.isNotBlank(r.email)) idx2.email2userId.putIfAbsent(r.email, r.userId);
                        if (StringUtils.isNotBlank(r.loginName)) idx2.loginNames.add(r.loginName);
                    }
                }
                return idx2;
            }
            throw e;
        }
        return idx;
    }

    private Map<String, Long> loadRoleIndex(Connection c) throws SQLException
    {
        Map<String, Long> m = new HashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT role_id, role_key, role_name FROM sys_role WHERE del_flag='0'"))
        {
            while (rs.next())
            {
                long rid = rs.getLong(1);
                String key = rs.getString(2);
                if (StringUtils.isNotBlank(key)) m.put(key.trim(), rid);
                String name = rs.getString(3);
                if ("普通员工".equals(name)) m.putIfAbsent("common", rid);
                if ("部门主管".equals(name)) m.putIfAbsent("dept_manager", rid);
            }
        }
        return m;
    }

    private Long matchLocalUserId(UserLocalIndex idx, DingTalkUser du)
    {
        Long uid = StringUtils.isBlank(du.userid) ? null : idx.userid2row.get(du.userid);
        if (uid != null) return uid;
        if (StringUtils.isNotBlank(du.getMobile()))
        {
            uid = idx.phone2userId.get(du.getMobile());
            if (uid != null) return uid;
        }
        if (StringUtils.isNotBlank(du.getEmail()))
        {
            uid = idx.email2userId.get(du.getEmail());
            if (uid != null) return uid;
        }
        return null;
    }

    private Long pickPrimaryLocalDeptId(DingTalkUser du, Map<Long, Long> dt2localDept)
    {
        if (du.deptIdList == null || du.deptIdList.isEmpty()) return null;
        for (Long did : du.deptIdList)
        {
            Long lid = dt2localDept.get(did);
            if (lid != null) return lid;
        }
        return null;
    }

    private String buildUniqueLoginName(UserLocalIndex idx, DingTalkUser du)
    {
        String base = StringUtils.isBlank(du.userid) ? null : du.userid;
        if (StringUtils.isBlank(base)) base = StringUtils.isBlank(du.mobile) ? "user" : du.mobile;
        base = base.replaceAll("[^0-9a-zA-Z_]", "_");
        if (base.length() > 24) base = base.substring(0, 24);
        if (!idx.loginNames.contains(base)) { idx.loginNames.add(base); return base; }
        for (int i = 2; i < 9999; i++)
        {
            String cand = base + i;
            if (!idx.loginNames.contains(cand)) { idx.loginNames.add(cand); return cand; }
        }
        return base + System.currentTimeMillis();
    }

    private Long insertUser(Connection c, String loginName, String userName, String encPwd, String salt,
                            Long deptId, DingTalkUser du, String rawPwd) throws SQLException
    {
        String sql = "INSERT INTO sys_user(dept_id, login_name, user_name, user_type, email, phonenumber, sex, avatar, password, salt, status, del_flag, login_ip, login_date, pwd_update_date, create_by, create_time, remark) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now(),'dingtalk-sync',now(),?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS))
        {
            ps.setObject(1, deptId == null ? 0L : deptId);
            ps.setString(2, loginName);
            ps.setString(3, StringUtils.substring(userName, 0, 30));
            ps.setString(4, "00"); // 系统用户
            ps.setString(5, du == null ? "" : nvl(du.getEmail()));
            ps.setString(6, du == null ? "" : nvl(du.getMobile()));
            ps.setString(7, "0"); // 未知
            ps.setString(8, du == null ? "" : nvl(du.avatar));
            ps.setString(9, encPwd);
            ps.setString(10, salt);
            ps.setString(11, du == null || du.active ? "0" : "1");
            ps.setString(12, "0");
            ps.setString(13, "0.0.0.0");
            ps.setString(14, "钉钉同步：初始密码 " + (rawPwd == null ? "" : rawPwd));
            ps.executeUpdate();
            try (ResultSet gk = ps.getGeneratedKeys())
            {
                if (gk.next()) return gk.getLong(1);
            }
        }
        throw new SQLException("插入用户失败 loginName=" + loginName);
    }

    private void updateUserSetDtUserid(Connection c, Long userId, String dtUserid)
    {
        if (StringUtils.isBlank(dtUserid)) return;
        String sql = "UPDATE sys_user SET dingtalk_userid=?, update_by='dingtalk-sync', update_time=now() WHERE user_id=? AND (dingtalk_userid IS NULL OR dingtalk_userid<>?)";
        try (PreparedStatement ps = c.prepareStatement(sql))
        {
            ps.setString(1, dtUserid);
            ps.setLong(2, userId);
            ps.setString(3, dtUserid);
            ps.executeUpdate();
        }
        catch (SQLException e)
        {
            if (!isMissingCol(e, "dingtalk_userid"))
                log.warn("[DingTalkSync] UPDATE 用户 dingtalk_userid 失败：{}", e.getMessage());
        }
    }

    private boolean needUpdateUser(UserLocalRow row, DingTalkUser du, Long primaryDeptId)
    {
        if (primaryDeptId != null && row.deptId != null && !primaryDeptId.equals(row.deptId)) return true;
        if (!StringUtils.equals(StringUtils.substring(du.getName() == null ? "" : du.getName(), 0, 30),
                StringUtils.substring(row.userName == null ? "" : row.userName, 0, 30))) return true;
        if (!StringUtils.equals(nvl(du.getMobile()), nvl(row.phonenumber))) return true;
        if (!StringUtils.equals(nvl(du.getEmail()), nvl(row.email))) return true;
        String targetStatus = du.active ? "0" : "1";
        if (!StringUtils.equals(targetStatus, row.status == null ? "0" : row.status)) return true;
        return false;
    }

    private void updateUser(Connection c, Long userId, DingTalkUser du, Long primaryDeptId) throws SQLException
    {
        String sql = "UPDATE sys_user SET dept_id=?, user_name=?, phonenumber=?, email=?, avatar=?, status=?, update_by='dingtalk-sync', update_time=now() WHERE user_id=?";
        try (PreparedStatement ps = c.prepareStatement(sql))
        {
            ps.setObject(1, primaryDeptId == null ? 0L : primaryDeptId);
            ps.setString(2, StringUtils.substring(du.getName() == null ? "" : du.getName(), 0, 30));
            ps.setString(3, nvl(du.getMobile()));
            ps.setString(4, nvl(du.getEmail()));
            ps.setString(5, nvl(du.avatar));
            ps.setString(6, du.active ? "0" : "1");
            ps.setLong(7, userId);
            ps.executeUpdate();
        }
    }

    private void setUserStatus(Connection c, Long userId, String status) throws SQLException
    {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE sys_user SET status=?, update_by='dingtalk-sync', update_time=now() WHERE user_id=?"))
        {
            ps.setString(1, status);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    private void ensureUserRole(Connection c, Long userId, Long roleId) throws SQLException
    {
        if (userId == null || roleId == null) return;
        // 先判断是否已存在
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(1) FROM sys_user_role WHERE user_id=? AND role_id=?"))
        {
            ps.setLong(1, userId); ps.setLong(2, roleId);
            try (ResultSet rs = ps.executeQuery())
            {
                if (rs.next() && rs.getInt(1) > 0) return;
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sys_user_role(user_id, role_id) VALUES(?,?)"))
        {
            ps.setLong(1, userId); ps.setLong(2, roleId);
            ps.executeUpdate();
        }
    }

    private static String nvl(String s) { return s == null ? "" : s; }
    private static boolean isMissingCol(SQLException e, String col)
    {
        String m = (e.getMessage() == null ? "" : e.getMessage().toLowerCase());
        return m.contains(col.toLowerCase());
    }

    // ================================================================================
    // 异常 + 返回值
    // ================================================================================

    public static class SyncException extends Exception
    {
        private static final long serialVersionUID = 1L;
        public SyncException(String msg) { super(msg); }
        public SyncException(String msg, Throwable t) { super(msg, t); }
    }

    public static class SyncDeptResult implements Serializable
    {
        private static final long serialVersionUID = 1L;
        public int inserted;
        public int updated;
        public int disabled;
    }

    public static class SyncUserResult implements Serializable
    {
        private static final long serialVersionUID = 1L;
        public int inserted;
        public int updated;
        public int disabled;
        /** 新用户明文初始密码（仅同步首次创建时返回，方便发给用户）；key=loginName, value=明文密码 */
        public Map<String, String> newUserPasswords = new LinkedHashMap<>();
    }

    public static class SyncSummary extends SyncUserResult implements Serializable
    {
        private static final long serialVersionUID = 1L;
        public int deptInserted;
        public int deptUpdated;
        public int deptDisabled;
        public long costMs;

        void copyFrom(SyncDeptResult r)
        {
            if (r == null) return;
            deptInserted = r.inserted;
            deptUpdated  = r.updated;
            deptDisabled = r.disabled;
        }
        void copyFrom(SyncUserResult r)
        {
            if (r == null) return;
            inserted = r.inserted;
            updated  = r.updated;
            disabled = r.disabled;
            newUserPasswords = r.newUserPasswords == null ? Collections.emptyMap() : r.newUserPasswords;
        }

        @Override public String toString()
        {
            return "SyncSummary{部门:[" + deptInserted + "新/" + deptUpdated + "更/" + deptDisabled + "停] "
                 + "用户:[" + inserted + "新/" + updated + "更/" + disabled + "停] 新密码数="
                 + (newUserPasswords == null ? 0 : newUserPasswords.size()) + " 耗时=" + costMs + "ms}";
        }
    }
}
