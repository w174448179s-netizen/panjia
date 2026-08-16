-- ====================================================================
-- 盘家 - 钉钉通知模块 初始化 SQL
-- 说明：
--   1) 本脚本需在主 panjia.sql 执行后，进行增量升级时执行一次。
--   2) 同时兼容 MySQL 5.7+ / PostgreSQL 10+。若执行报「重复键/主键冲突」请忽略，表示已经初始化过。
--   3) 键名与 DingTalkConfig.java 中 KEY_* 常量一一对应，仅 5 个键。
-- ====================================================================

-- --------------------------------------------------------------------
-- A. 参数配置（sys_config）— 5 个钉钉参数键
-- --------------------------------------------------------------------
-- A1. 启用开关
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
VALUES ('钉钉通知-是否启用', 'dingtalk.enabled', 'true', 'Y', 'admin', now(),
        '钉钉通知总开关，true=启用 false=关闭；关闭后所有发送接口直接报错：未启用');

-- A2. App ID
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
VALUES ('钉钉通知-App ID', 'dingtalk.app_id', '82d41356-0ddb-4c63-b4c4-89c98ca49a47', 'Y', 'admin', now(),
        '钉钉开放平台 应用详情页 → 基础信息 → App ID');

-- A3. AgentId
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
VALUES ('钉钉通知-AgentId', 'dingtalk.agent_id', '4868752593', 'Y', 'admin', now(),
        '原企业内部应用 AgentId（数字）；发送工作通知必填');

-- A4. Client ID（原 AppKey / SuiteKey）
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
VALUES ('钉钉通知-Client ID', 'dingtalk.client_id', 'dingaxohfuw7m7qnmplb', 'Y', 'admin', now(),
        '钉钉开放平台 应用详情页 → 凭证与基础信息 → Client ID（原 AppKey / SuiteKey）');

-- A5. Client Secret（原 AppSecret / SuiteSecret）
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
VALUES ('钉钉通知-Client Secret', 'dingtalk.client_secret', '3Xd-ynS3hJNNMwo80l5ucQXYxkDfo3spyzeOwwFgXeOgNR9QoHU0_UOejtibzxmm', 'Y', 'admin', now(),
        '钉钉开放平台 应用详情页 → 凭证与基础信息 → Client Secret（敏感信息，建议定期更换）');

-- A6. CorpId（企业 ID）：JSAPI requestAuthCode 必需参数
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
VALUES ('钉钉-企业CorpId', 'dingtalk.corp_id', '', 'Y', 'admin', now(),
        '钉钉开放平台 → 企业信息 / 基本信息 → 企业ID（CorpId）；JSAPI 免登 dd.runtime.permission.requestAuthCode 必须传合法 corpId，否则报错「corpId 不合法」');


-- --------------------------------------------------------------------
-- B. 菜单与按钮权限（sys_menu）
--   父菜单：系统管理 menu_id=1（order_num 在 7=参数设置 与 9=日志管理 之间，取 order_num=8）
-- --------------------------------------------------------------------

-- B1. 通知配置 - 主菜单
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES (115, '通知配置', 1, 8, '/system/notice', '', 'C', '0', '1', 'system:notice:view', 'fa fa-bell-o', 'admin', now(), '钉钉工作通知等消息通道配置');

-- B2. 三个功能按钮权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES (2000, '通知查询', 115, 1, '#', '', 'F', '0', '1', 'system:notice:view', '#', 'admin', now(), '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES (2001, '通知编辑', 115, 2, '#', '', 'F', '0', '1', 'system:notice:edit', '#', 'admin', now(), '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES (2002, '测试发送', 115, 3, '#', '', 'F', '0', '1', 'system:notice:test', '#', 'admin', now(), '');

-- --------------------------------------------------------------------
-- C. 超级管理员角色默认拥有新菜单权限（sys_role_menu）
--    约定：角色 1 = admin / super admin（如不同请按实际 role_id 调整）
-- --------------------------------------------------------------------
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 115);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 2000);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 2001);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 2002);
