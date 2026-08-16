-- ============================================================
-- PanJia PostgreSQL 全量升级脚本（钉钉免登+通知配置+组织同步）
-- 适用数据库：PostgreSQL 12+   执行顺序：仅此一个文件即可（已合并 3 个 MySQL 版脚本 + PG 方言适配）
-- 执行前：确认 docker exec -i panjia-postgres psql -U panjia -d panjia
-- 幂等：所有 DDL 均 IF NOT EXISTS；所有 INSERT 均 WHERE NOT EXISTS / ON CONFLICT DO NOTHING
-- ============================================================

SET client_min_messages TO WARNING;

-- ============================================================
-- 0) 建 sys_notice 通知公告表（缺失表）
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_notice (
    notice_id       BIGINT          NOT NULL,
    notice_title    VARCHAR(50)     NOT NULL,
    notice_type     CHAR(1)         NOT NULL,
    notice_content  TEXT,
    status          CHAR(1)         DEFAULT '0',
    create_by       VARCHAR(64)     DEFAULT '',
    create_time     TIMESTAMP,
    update_by       VARCHAR(64)     DEFAULT '',
    update_time     TIMESTAMP,
    remark          VARCHAR(500)
);
COMMENT ON TABLE  sys_notice              IS '通知公告表';
COMMENT ON COLUMN sys_notice.notice_id    IS '公告ID';
COMMENT ON COLUMN sys_notice.notice_title IS '公告标题';
COMMENT ON COLUMN sys_notice.notice_type  IS '公告类型（1通知 2公告）';
COMMENT ON COLUMN sys_notice.notice_content IS '公告内容';
COMMENT ON COLUMN sys_notice.status       IS '公告状态（0正常 1关闭）';
COMMENT ON COLUMN sys_notice.create_by    IS '创建者';
COMMENT ON COLUMN sys_notice.create_time  IS '创建时间';
COMMENT ON COLUMN sys_notice.update_by    IS '更新者';
COMMENT ON COLUMN sys_notice.update_time  IS '更新时间';
COMMENT ON COLUMN sys_notice.remark       IS '备注';

DO $$ BEGIN IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname='pk_sys_notice'
) THEN
    ALTER TABLE sys_notice ADD CONSTRAINT pk_sys_notice PRIMARY KEY (notice_id);
END IF; END $$;

-- 建索引
CREATE INDEX IF NOT EXISTS idx_sys_notice_type ON sys_notice(notice_type);

-- 插入初始公告示例（notice_id=1）
INSERT INTO sys_notice(notice_id, notice_title, notice_type, notice_content, status, create_by, create_time, remark)
SELECT 1, '欢迎使用盘家系统', '2', '<h3>欢迎使用盘家后台管理系统</h3><p>本系统已接入钉钉免登、钉钉工作通知、钉钉组织自动同步。</p>', '0', 'admin', now(), '初始化公告'
WHERE NOT EXISTS (SELECT 1 FROM sys_notice WHERE notice_id=1);


-- ============================================================
-- 1) 通知配置菜单：menu_id=115 挂 parent_id=1（系统管理目录）
-- ============================================================
INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, url, target, menu_type, visible, is_refresh, perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES (115, '通知配置', 1, 6, '/system/notice', '', 'C', '0', '1', NULL, 'message', 'admin', now(), '', NULL, '通知配置与钉钉推送菜单')
ON CONFLICT (menu_id) DO NOTHING;

-- 按钮权限 2000 编辑
INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, url, target, menu_type, visible, is_refresh, perms, icon, create_by, create_time, remark)
VALUES (2000, '通知配置编辑', 115, 1,  '#', '', 'F', '0', '1', 'system:notice:edit', '#', 'admin', now(), '通知配置-编辑按钮权限')
ON CONFLICT (menu_id) DO NOTHING;

-- 按钮权限 2001 发钉钉推送测试
INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, url, target, menu_type, visible, is_refresh, perms, icon, create_by, create_time, remark)
VALUES (2001, '钉钉推送测试', 115, 2,  '#', '', 'F', '0', '1', 'system:notice:send', '#', 'admin', now(), '通知配置-发送钉钉测试按钮权限')
ON CONFLICT (menu_id) DO NOTHING;

-- 按钮权限 2002 看推送日志
INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, url, target, menu_type, visible, is_refresh, perms, icon, create_by, create_time, remark)
VALUES (2002, '推送日志查询', 115, 3,  '#', '', 'F', '0', '1', 'system:notice:log',  '#', 'admin', now(), '通知配置-推送历史按钮权限')
ON CONFLICT (menu_id) DO NOTHING;

-- 按钮权限 2003 组织同步
INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, url, target, menu_type, visible, is_refresh, perms, icon, create_by, create_time, remark)
VALUES (2003, '组织同步',     115, 4,  '#', '', 'F', '0', '1', 'system:notice:sync', '#', 'admin', now(), '通知配置-组织同步按钮权限')
ON CONFLICT (menu_id) DO NOTHING;

-- ====== 超管角色(role_id=1) 授权：menu_id=115 主菜单 + 4 个按钮 menu_id 2000~2003 ======
INSERT INTO sys_role_menu(role_id, menu_id)
SELECT 1, m.id FROM (VALUES (115),(2000),(2001),(2002),(2003)) AS m(id)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=1 AND rm.menu_id=m.id
);


-- ============================================================
-- 2) SSO 免登升级：sys_user 加 dingtalk_userid 列
-- ============================================================
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS dingtalk_userid VARCHAR(64);
COMMENT ON COLUMN sys_user.dingtalk_userid IS '钉钉用户userid（绑定免登录用）';
CREATE INDEX IF NOT EXISTS idx_sys_user_dingtalk_userid ON sys_user(dingtalk_userid);


-- ============================================================
-- 3) 同步升级：sys_dept 加 dingtalk_dept_id 列
-- ============================================================
ALTER TABLE sys_dept ADD COLUMN IF NOT EXISTS dingtalk_dept_id BIGINT;
COMMENT ON COLUMN sys_dept.dingtalk_dept_id IS '钉钉部门ID（组织同步映射用）';
CREATE INDEX IF NOT EXISTS idx_sys_dept_dingtalk_dept_id ON sys_dept(dingtalk_dept_id);


-- ============================================================
-- 4) 预置两个默认角色：普通员工(common, role_id=10) + 部门主管(dept_manager, role_id=11)
-- ============================================================
INSERT INTO sys_role(role_id, role_name, role_key, role_sort, data_scope, status, del_flag, create_by, create_time, update_by, update_time, remark)
SELECT 10, '普通员工',     'common',       50, '1', '0', '0', 'admin', now(), '', NULL, '钉钉同步时默认授予所有员工（由 DingTalkSyncService 自动维护）'
WHERE  NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key='common');

INSERT INTO sys_role(role_id, role_name, role_key, role_sort, data_scope, status, del_flag, create_by, create_time, update_by, update_time, remark)
SELECT 11, '部门主管',     'dept_manager', 40, '2', '0', '0', 'admin', now(), '', NULL, '钉钉同步时授予部门 leader 或部门负责人（由 DingTalkSyncService 自动维护）'
WHERE  NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key='dept_manager');


-- ============================================================
-- 5) 钉钉应用配置参数：sys_config（config_id 从 1001 起，避免与现有 1-9 冲突）
--    键名与 DingTalkConfig.java 中 KEY_* 常量一一对应，仅保留代码实际读取的 6 个键
-- ============================================================

-- 5a. 清理旧版多余参数键（app_key/app_secret/sync.*），如已执行过旧脚本则删除遗留
DELETE FROM sys_config WHERE config_key IN ('dingtalk.app_key', 'dingtalk.app_secret',
    'dingtalk.sync.enabled', 'dingtalk.sync.cron', 'dingtalk.sync.initPwdPrefix',
    'dingtalk.sync.disable_leaver', 'dingtalk.sync.password_rule');

INSERT INTO sys_config(config_id, config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT 1001, '钉钉通知-是否启用',       'dingtalk.enabled',       'true', 'Y', 'admin', now(),
       '钉钉通知总开关，true=启用 false=关闭；关闭后所有发送接口直接报错：未启用'
WHERE  NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='dingtalk.enabled');

INSERT INTO sys_config(config_id, config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT 1002, '钉钉通知-App ID',         'dingtalk.app_id',        '82d41356-0ddb-4c63-b4c4-89c98ca49a47', 'Y', 'admin', now(),
       '钉钉开放平台 应用详情页 → 基础信息 → App ID'
WHERE  NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='dingtalk.app_id');

INSERT INTO sys_config(config_id, config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT 1003, '钉钉通知-AgentId',        'dingtalk.agent_id',      '4868752593', 'Y', 'admin', now(),
       '原企业内部应用 AgentId（数字）；发送工作通知必填'
WHERE  NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='dingtalk.agent_id');

INSERT INTO sys_config(config_id, config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT 1004, '钉钉通知-Client ID',      'dingtalk.client_id',     'dingaxohfuw7m7qnmplb', 'Y', 'admin', now(),
       'Client ID（原 AppKey / SuiteKey）'
WHERE  NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='dingtalk.client_id');

INSERT INTO sys_config(config_id, config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT 1005, '钉钉通知-Client Secret',  'dingtalk.client_secret',  '3Xd-ynS3hJNNMwo80l5ucQXYxkDfo3spyzeOwwFgXeOgNR9QoHU0_UOejtibzxmm', 'Y', 'admin', now(),
       'Client Secret（原 AppSecret / SuiteSecret，敏感信息，建议定期更换）'
WHERE  NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='dingtalk.client_secret');

-- A6. CorpId（企业 ID）：JSAPI requestAuthCode 必需参数，钉钉开放平台 → 企业信息 → 企业ID
INSERT INTO sys_config(config_id, config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT 1006, '钉钉-企业CorpId',         'dingtalk.corp_id',       '', 'Y', 'admin', now(),
       '钉钉开放平台 → 企业信息 / 基本信息 → 企业ID（CorpId）；JSAPI 免登 dd.runtime.permission.requestAuthCode 必须传合法 corpId，否则报错「corpId 不合法」'
WHERE  NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='dingtalk.corp_id');
