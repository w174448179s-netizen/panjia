-- ====================================================================
-- 盘家 - 钉钉免登(SSO)升级 SQL
-- 说明：
--   1) 执行前提：已先执行 sql/panjia-notice-init.sql（钉钉5个配置键）
--   2) 增量升级脚本，仅需执行一次。若报"列已存在/重复"可忽略。
--   3) 兼容 MySQL 5.7+/8.0+ 与 PostgreSQL 10+。
-- ====================================================================

-- A. sys_user 增加钉钉 userid 绑定列（用于登录后一键自动绑定，下次免登不走手机号匹配）
--    MySQL 语法：
ALTER TABLE sys_user ADD COLUMN dingtalk_userid VARCHAR(64) DEFAULT NULL COMMENT '钉钉免登绑定的用户userid（按手机号首次匹配成功后自动回填）';
--    如果用 PostgreSQL，请改用下面这句（注释上面的）：
-- ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS dingtalk_userid VARCHAR(64);

-- B. 加索引（可选，大系统建议加上；几百用户可跳过）
CREATE INDEX idx_sys_user_dingtalk_userid ON sys_user(dingtalk_userid);
-- PostgreSQL 用下面这句代替（避免重复索引报错）：
-- CREATE INDEX IF NOT EXISTS idx_sys_user_dingtalk_userid ON sys_user(dingtalk_userid);
