package com.panjia.web.controller.system;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.panjia.common.annotation.Log;
import com.panjia.common.core.controller.BaseController;
import com.panjia.common.core.domain.AjaxResult;
import com.panjia.common.enums.BusinessType;
import com.panjia.common.utils.StringUtils;
import com.panjia.framework.dingtalk.DingTalkConfig;
import com.panjia.framework.dingtalk.DingTalkNoticeService;
import com.panjia.framework.dingtalk.DingTalkNoticeService.DingTalkSendResult;
import com.panjia.system.domain.SysConfig;
import com.panjia.system.mapper.SysConfigMapper;
import com.panjia.system.service.ISysConfigService;

/**
 * 通知配置（钉钉）操作处理
 * <p>
 * 包含：
 * <ul>
 *   <li>GET  /system/notice        通知配置页面</li>
 *   <li>POST /system/notice/getConfig   获取当前配置（secret 脱敏）</li>
 *   <li>POST /system/notice/saveConfig  保存 5 个配置键（存在则更新，不存在则插入）</li>
 *   <li>POST /system/notice/testSend    按手机号测试发送文本消息</li>
 *   <li>POST /system/notice/sync        手动触发【钉钉组织架构/用户/角色】全量同步</li>
 * </ul>
 */
@Controller
@RequestMapping("/system/notice")
public class SysNoticeController extends BaseController
{
    private static final String MASKED_SECRET = "******";

    private String prefix = "system/notice";

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private SysConfigMapper configMapper;

    @Autowired
    private DingTalkConfig dingTalkConfig;

    @Autowired
    private DingTalkNoticeService dingTalkNoticeService;

    @Autowired
    private com.panjia.framework.dingtalk.DingTalkSyncService dingTalkSyncService;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    private static final String[][] DICT = {
            // key,                    名称,               类型(Y=内置)
            { DingTalkConfig.KEY_ENABLED,       "钉钉通知-是否启用",       "Y" },
            { DingTalkConfig.KEY_APP_ID,        "钉钉通知-App ID",        "Y" },
            { DingTalkConfig.KEY_AGENT_ID,      "钉钉通知-AgentId",       "Y" },
            { DingTalkConfig.KEY_CLIENT_ID,     "钉钉通知-Client ID",     "Y" },
            { DingTalkConfig.KEY_CLIENT_SECRET, "钉钉通知-Client Secret", "Y" },
            { DingTalkConfig.KEY_CORP_ID,       "钉钉-企业CorpId",        "Y" }
    };

    @RequiresPermissions("system:notice:view")
    @GetMapping()
    public String notice(ModelMap mmap)
    {
        return prefix + "/notice";
    }

    /** 获取当前配置（Client Secret 脱敏返回，前端判断未改就不传此字段） */
    @RequiresPermissions("system:notice:view")
    @PostMapping("/getConfig")
    @ResponseBody
    public AjaxResult getConfig()
    {
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("enabled",       nvl(dingTalkConfig.isEnabled() ? "true" : "false", "false"));
        cfg.put("appId",         nvl(dingTalkConfig.getAppId(), ""));
        cfg.put("agentId",       nvl(dingTalkConfig.getAgentIdStr(), ""));
        cfg.put("clientId",      nvl(dingTalkConfig.getClientId(), ""));
        String secret = dingTalkConfig.getClientSecret();
        cfg.put("clientSecret",  StringUtils.isBlank(secret) ? "" : MASKED_SECRET);
        cfg.put("corpId",        nvl(dingTalkConfig.getCorpId(), ""));
        return success(cfg);
    }

    /** 保存配置（Client Secret 传的是掩码值时跳过不更新） */
    @RequiresPermissions("system:notice:edit")
    @Log(title = "通知配置", businessType = BusinessType.UPDATE)
    @PostMapping("/saveConfig")
    @ResponseBody
    public AjaxResult saveConfig(
            @RequestParam(value = "enabled",       required = false) String enabled,
            @RequestParam(value = "appId",         required = false) String appId,
            @RequestParam(value = "agentId",       required = false) String agentId,
            @RequestParam(value = "clientId",      required = false) String clientId,
            @RequestParam(value = "clientSecret",  required = false) String clientSecret,
            @RequestParam(value = "corpId",        required = false) String corpId)
    {
        Map<String, String> toSave = new LinkedHashMap<>();
        toSave.put(DingTalkConfig.KEY_ENABLED,       StringUtils.isBlank(enabled) ? "false" : enabled);
        toSave.put(DingTalkConfig.KEY_APP_ID,        nvl(appId, ""));
        toSave.put(DingTalkConfig.KEY_AGENT_ID,      nvl(agentId, ""));
        toSave.put(DingTalkConfig.KEY_CLIENT_ID,     nvl(clientId, ""));
        toSave.put(DingTalkConfig.KEY_CORP_ID,       nvl(corpId, ""));
        // clientSecret 传 MASKED_SECRET 或空，保持原值不覆盖
        if (!StringUtils.isBlank(clientSecret) && !MASKED_SECRET.equals(clientSecret))
        {
            toSave.put(DingTalkConfig.KEY_CLIENT_SECRET, clientSecret);
        }
        try
        {
            for (String[] def : DICT)
            {
                String key = def[0];
                if (!toSave.containsKey(key)) continue; // 不需要写的跳过（如secret不变）
                String name = def[1];
                String type = def[2];
                String value = toSave.get(key);
                upsertConfig(key, name, type, value);
            }
            // 清理缓存，使新配置生效
            configService.resetConfigCache();
            dingTalkNoticeService.clearTokenCache();
        }
        catch (Exception e)
        {
            logger.error("保存通知配置失败", e);
            return error("保存失败：" + e.getMessage());
        }
        return success("保存成功");
    }

    /** 测试发送：接收者手机号 + 文本内容 */
    @RequiresPermissions("system:notice:test")
    @Log(title = "通知配置-测试发送", businessType = BusinessType.OTHER)
    @PostMapping("/testSend")
    @ResponseBody
    public AjaxResult testSend(
            @RequestParam("mobile") String mobile,
            @RequestParam("content") String content)
    {
        if (StringUtils.isBlank(mobile)) return error("请输入接收者手机号");
        if (StringUtils.isBlank(content)) return error("请输入消息内容");
        try
        {
            DingTalkSendResult r = dingTalkNoticeService.sendTextByMobile(mobile.trim(), content);
            if (r.isSuccess())
                return success("发送成功，任务ID=" + r.getTaskId());
            return error("发送失败，请查看日志");
        }
        catch (com.panjia.framework.dingtalk.DingTalkNoticeService.DingTalkException e)
        {
            logger.warn("钉钉测试发送失败：{}", e.getMessage());
            return error(e.getMessage());
        }
        catch (Exception e)
        {
            logger.error("钉钉测试发送异常", e);
            return error("发送异常：" + e.getMessage());
        }
    }

    // ======= 辅助 =======

    private void upsertConfig(String key, String name, String type, String value)
    {
        SysConfig existing = configMapper.checkConfigKeyUnique(key);
        SysConfig cfg = new SysConfig();
        cfg.setConfigKey(key);
        cfg.setConfigName(name);
        cfg.setConfigValue(value);
        cfg.setConfigType(type);
        if (existing == null)
        {
            cfg.setCreateBy(getLoginName());
            configService.insertConfig(cfg);
        }
        else
        {
            cfg.setConfigId(existing.getConfigId());
            cfg.setUpdateBy(getLoginName());
            configService.updateConfig(cfg);
        }
    }

    private static String nvl(String s, String d) { return StringUtils.isBlank(s) ? d : s; }

    // ================== 钉钉组织架构/用户同步 ==================

    @RequiresPermissions("system:notice:sync")
    @Log(title = "通知配置-组织同步", businessType = BusinessType.OTHER)
    @PostMapping("/sync")
    @ResponseBody
    public AjaxResult syncNow()
    {
        try
        {
            com.panjia.framework.dingtalk.DingTalkSyncService.SyncSummary s = dingTalkSyncService.syncAll();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deptInserted", s.deptInserted);
            data.put("deptUpdated",  s.deptUpdated);
            data.put("deptDisabled", s.deptDisabled);
            data.put("userInserted", s.inserted);
            data.put("userUpdated",  s.updated);
            data.put("userDisabled", s.disabled);
            data.put("costMs",       s.costMs);
            // 新用户明文密码只显示前 5 条，剩下折叠
            int shown = Math.min(5, s.newUserPasswords == null ? 0 : s.newUserPasswords.size());
            java.util.List<Map<String, String>> pwdList = new java.util.ArrayList<>();
            if (s.newUserPasswords != null)
            {
                int i = 0;
                for (Map.Entry<String, String> e : s.newUserPasswords.entrySet())
                {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("loginName", e.getKey());
                    row.put("password",  i < shown ? e.getValue() : "");
                    pwdList.add(row);
                    i++;
                }
            }
            data.put("newUsers", pwdList);
            data.put("newUserCount", pwdList.size());
            return AjaxResult.success("同步完成", data);
        }
        catch (com.panjia.framework.dingtalk.DingTalkNoticeService.DingTalkException e)
        {
            logger.warn("钉钉同步失败（接口异常）：{}", e.getMessage());
            return error("钉钉接口调用失败：" + e.getMessage());
        }
        catch (com.panjia.framework.dingtalk.DingTalkSyncService.SyncException e)
        {
            logger.warn("钉钉同步失败（SQL/落库异常）：{}", e.getMessage());
            return error("落库失败：" + e.getMessage());
        }
        catch (Exception e)
        {
            logger.error("钉钉同步未知异常", e);
            return error("同步异常：" + e.getMessage());
        }
    }

    // ================== 公告展示（首页顶部 notice 下拉 + 详情） ==================

    /** PG JDBC 列名按数据库真实列名返回（带下划线，或部分驱动会强制全小写去下划线）；此处双保险映射到前端期望的驼峰键 */
    private static final java.util.Map<String, String> NOTICE_KEY_MAP = new java.util.HashMap<String, String>() {{
        // 下划线命名（PG 真实列名）
        put("notice_id",       "noticeId");
        put("notice_title",    "noticeTitle");
        put("notice_type",     "noticeType");
        put("notice_content",  "noticeContent");
        put("create_time",     "createTime");
        put("create_by",       "createBy");
        // 兼容：其他方言驱动可能给全小写连续
        put("noticeid",       "noticeId");
        put("noticetitle",    "noticeTitle");
        put("noticetype",     "noticeType");
        put("noticecontent",  "noticeContent");
        put("createtime",     "createTime");
        put("createby",       "createBy");
    }};

    private static java.text.SimpleDateFormat SDF_DT() { return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm"); }

    private Map<String, Object> camelNoticeMap(Map<String, Object> raw)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (Map.Entry<String, Object> e : raw.entrySet())
        {
            String k = e.getKey() == null ? "" : e.getKey().toLowerCase();
            String nk = NOTICE_KEY_MAP.getOrDefault(k, k);
            out.put(nk, e.getValue());
        }
        // 顺便生成模板直接用的时间字符串
        Object ct = out.get("createTime");
        if (ct instanceof java.util.Date)
        {
            try { out.put("createTimeText", SDF_DT().format((java.util.Date) ct)); }
            catch (Exception ignore) {}
        }
        else if (ct != null)
        {
            String s = ct.toString();
            if (s.length() >= 16) out.put("createTimeText", s.substring(0, 16));
            else                  out.put("createTimeText", s);
        }
        return out;
    }

    /**
     * 首页公告列表：GET /system/notice/listTop
     * 返回最新 10 条正常状态公告，供首页头部铃铛下拉渲染
     */
    @GetMapping("/listTop")
    @ResponseBody
    public AjaxResult listTop()
    {
        Map<String, Object> result = new LinkedHashMap<>();
        if (jdbcTemplate == null)
        {
            result.put("unreadCount", 0);
            result.put("data", new java.util.ArrayList<>());
            return success(result);
        }
        try
        {
            String sql = "SELECT notice_id, notice_title, notice_type, notice_content, create_time "
                       + "FROM sys_notice WHERE status='0' ORDER BY notice_id DESC LIMIT 10";
            List<Map<String, Object>> rawList = jdbcTemplate.queryForList(sql);
            java.util.List<Map<String, Object>> outList = new java.util.ArrayList<>();
            for (Map<String, Object> raw : rawList) outList.add(camelNoticeMap(raw));
            result.put("unreadCount", outList.size());
            result.put("data", outList);
            return success(result);
        }
        catch (Exception e)
        {
            logger.warn("查询公告列表失败", e);
            result.put("unreadCount", 0);
            result.put("data", new java.util.ArrayList<>());
            return success(result);
        }
    }

    /**
     * 标记公告已读：POST /system/notice/markRead（前端本地也会置灰，这里仅 200 兜底不落库）
     */
    @PostMapping("/markRead")
    @ResponseBody
    public AjaxResult markRead(@RequestParam("noticeId") String noticeId)
    {
        return success();
    }

    /**
     * 公告详情页（右侧抽屉 iframe 打开）：GET /system/notice/view/{noticeId}
     */
    @GetMapping("/view/{noticeId}")
    public String view(@PathVariable("noticeId") Long noticeId, ModelMap mmap)
    {
        Map<String, Object> notice = null;
        if (jdbcTemplate != null)
        {
            try
            {
                String sql = "SELECT notice_id, notice_title, notice_type, notice_content, create_time, create_by "
                           + "FROM sys_notice WHERE notice_id=?";
                List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, noticeId);
                if (!list.isEmpty()) notice = camelNoticeMap(list.get(0));
            }
            catch (Exception e)
            {
                logger.warn("查询公告详情失败 id={}", noticeId, e);
            }
        }
        if (notice == null)
        {
            notice = new LinkedHashMap<>();
            notice.put("noticeId", noticeId);
            notice.put("noticeTitle", "公告不存在或已删除");
            notice.put("noticeType", "2");
            notice.put("noticeContent", "<p style=\"color:#999;\">该公告可能已被移除，请返回公告列表查看。</p>");
            notice.put("createTime", null);
            notice.put("createBy", "system");
        }
        mmap.put("notice", notice);
        return prefix + "/view";
    }
}
