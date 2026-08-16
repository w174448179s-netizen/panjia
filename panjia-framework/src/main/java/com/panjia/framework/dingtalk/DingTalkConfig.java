package com.panjia.framework.dingtalk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.panjia.common.utils.StringUtils;
import com.panjia.system.service.ISysConfigService;

/**
 * 钉钉应用配置读取（参数存在 sys_config，键名前缀 dingtalk.）
 * <p>
 * 配置键：
 * <ul>
 *   <li>dingtalk.enabled       是否启用（true/false）</li>
 *   <li>dingtalk.app_id        App ID</li>
 *   <li>dingtalk.agent_id      原企业内部应用 AgentId（数字）</li>
 *   <li>dingtalk.client_id     Client ID（原 AppKey / SuiteKey）</li>
 *   <li>dingtalk.client_secret Client Secret（原 AppSecret / SuiteSecret）</li>
 *   <li>dingtalk.corp_id       企业 CorpId（钉钉开放平台 → 基本信息 → 企业ID；JSAPI requestAuthCode 必需参数）</li>
 * </ul>
 */
@Component
public class DingTalkConfig
{
    private static final Logger log = LoggerFactory.getLogger(DingTalkConfig.class);

    public static final String KEY_ENABLED       = "dingtalk.enabled";
    public static final String KEY_APP_ID        = "dingtalk.app_id";
    public static final String KEY_AGENT_ID      = "dingtalk.agent_id";
    public static final String KEY_CLIENT_ID     = "dingtalk.client_id";
    public static final String KEY_CLIENT_SECRET = "dingtalk.client_secret";
    public static final String KEY_CORP_ID       = "dingtalk.corp_id";

    @Autowired
    private ISysConfigService configService;

    public boolean isEnabled()
    {
        String v = get(KEY_ENABLED);
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "Y".equalsIgnoreCase(v);
    }

    public String getAppId()        { return get(KEY_APP_ID); }
    public String getAgentIdStr()   { return get(KEY_AGENT_ID); }
    public String getClientId()     { return get(KEY_CLIENT_ID); }
    public String getClientSecret() { return get(KEY_CLIENT_SECRET); }
    public String getCorpId()       { return get(KEY_CORP_ID); }

    public Long getAgentId()
    {
        try { String s = get(KEY_AGENT_ID); return StringUtils.isBlank(s) ? null : Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    /** 校验必填配置，返回 null 表示 OK，否则返回缺的字段名 */
    public String validate()
    {
        if (!isEnabled()) return "钉钉通知未启用（" + KEY_ENABLED + " != true）";
        if (StringUtils.isBlank(getAppId()))        return KEY_APP_ID + " 未配置";
        if (getAgentId() == null)                   return KEY_AGENT_ID + " 未配置或不是数字";
        if (StringUtils.isBlank(getClientId()))     return KEY_CLIENT_ID + " 未配置";
        if (StringUtils.isBlank(getClientSecret())) return KEY_CLIENT_SECRET + " 未配置";
        return null;
    }

    private String get(String key)
    {
        try { return configService.selectConfigByKey(key); }
        catch (Exception e)
        {
            log.warn("[DingTalkConfig] 读取 {} 失败：{}", key, e.getMessage());
            return null;
        }
    }
}
