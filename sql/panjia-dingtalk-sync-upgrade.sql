-- ====================================================================
-- 盘家 - 钉钉组织架构/用户/角色自动同步 升级 SQL
-- 执行顺序（必须）：
--   1) sql/panjia-notice-init.sql            （通知配置 + 菜单通知配置）
--   2) sql/panjia-dingtalk-sso-upgrade.sql   （sys_user 加 dingtalk_userid）
--   3) sql/panjia-dingtalk-sync-upgrade.sql  （本文件：部门列 + 角色 + 参数 + sync 按钮权限）
-- ====================================================================

-- --------------------------------------------------------------------
-- A. sys_dept 钉钉部门ID列 + 索引（MySQL版）
-- --------------------------------------------------------------------
ALTER TABLE sys_dept ADD COLUMN dingtalk_dept_id BIGINT DEFAULT NULL COMMENT '钉钉部门ID，同步时通过此字段与钉钉部门一一对应';
CREATE INDEX idx_sys_dept_dingtalk_dept_id ON sys_dept(dingtalk_dept_id);
-- PostgreSQL 请改用下面两句（注释掉上面两句）：
-- ALTER TABLE sys_dept ADD COLUMN IF NOT EXISTS dingtalk_dept_id BIGINT;
-- CREATE INDEX IF NOT EXISTS idx_sys_dept_dingtalk_dept_id ON sys_dept(dingtalk_dept_id);


-- --------------------------------------------------------------------
-- B. 预置 2 个角色（若已存在则不会插入，避免唯一键冲突）
-- --------------------------------------------------------------------
-- B1. 普通员工（role_key=common）→ 钉钉同步过来的员工默认都授予此角色
INSERT INTO sys_role (role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_by, create_time, remark)
SELECT '普通员工', 'common', 50, '1', 1, 1, '0', '0', 'admin', now(), '钉钉同步新用户默认授予；可在角色管理中自定义该角色的菜单/数据权限'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key = 'common');

-- B2. 部门主管（role_key=dept_manager）→ 钉钉部门负责人/leader=true 的用户额外授予
INSERT INTO sys_role (role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_by, create_time, remark)
SELECT '部门主管', 'dept_manager', 40, '2', 1, 1, '0', '0', 'admin', now(), '钉钉部门负责人默认附加；可自定义更高数据范围权限，如"本部门及以下"=2'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key = 'dept_manager');


-- --------------------------------------------------------------------
-- C. 菜单按钮权限：system:notice:sync（挂在通知配置主菜单 115 下，按钮权限 menu_id=2003）
-- --------------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 2003, '组织同步', 115, 4, '#', '', 'F', '0', '1', 'system:notice:sync', '#', 'admin', now(), ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2003);

-- 超管 role_id=1 默认拥有
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, 2003
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2003);
