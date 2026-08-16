package com.panjia.framework.dingtalk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import com.panjia.common.json.JSON;
import com.panjia.common.utils.StringUtils;
import com.panjia.common.utils.http.HttpUtils;

/**
 * 钉钉通知服务
 * <p>
 * 对外提供 3 个高层 API：
 * <ul>
 *   <li>{@link #sendTextByMobile(String, String)}  按单个手机号发送文本消息</li>
 *   <li>{@link #sendTextByMobiles(List, String)}  按多个手机号批量发送（同一条）</li>
 *   <li>{@link #sendMarkdownByMobile(String, String, String)}  按手机号发送Markdown</li>
 * </ul>
 * 底层封装：access_token 自动获取+缓存、手机号→userId 查询、工作通知 asyncsend 发送
 */
@Service
public class DingTalkNoticeService
{
    private static final Logger log = LoggerFactory.getLogger(DingTalkNoticeService.class);

    /** 旧版域名：topapi 系列（免登/通讯录/工作通知）官方无 v1.0 替代，仍走此域名，且同时兼容新版 accessToken */
    private static final String OAPI = "https://oapi.dingtalk.com";
    /** 新版域名：/v1.0/* REST 接口（获取 accessToken 等） */
    private static final String API_V1 = "https://api.dingtalk.com";

    @Autowired
    private DingTalkConfig cfg;

    // === access_token 缓存（线程安全：双检查 + volatile）===
    private volatile String cachedToken;
    private volatile long   cachedTokenExpireAt; // ms 时间戳
    /** 标记缓存 token 来自新版（v1.0）还是旧版（gettoken）接口，仅用于日志 */
    private volatile String cachedTokenChannel = "";

    /**
     * 拿 access_token，带缓存。
     * <p>优先走新版接口 POST /v1.0/oauth2/accessToken（JSON body：appKey/appSecret，
     * 响应 accessToken/expireIn，无 errcode 字段，出错时 HTTP 非 200）；
     * 新版失败（网络/参数/权限问题）自动回退旧版 GET /gettoken，保证可用性。
     * <p>注意：两种接口拿到的 token 均可用于 oapi 的 topapi 系列接口（access_token 查询参数）。
     */
    public synchronized String getAccessToken() throws DingTalkException
    {
        String err = cfg.validate();
        if (err != null) throw new DingTalkException(err);
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < cachedTokenExpireAt) return cachedToken;
        try
        {
            String token = null;
            long lifeSec = 0;
            String channel = "";
            // ===== 新版接口优先 =====
            try
            {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("appKey", cfg.getClientId());
                body.put("appSecret", cfg.getClientSecret());
                String resp = postJson(API_V1 + "/v1.0/oauth2/accessToken", body);
                if (StringUtils.isNotBlank(resp))
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = JSON.unmarshal(resp, Map.class);
                    String t = (String) m.get("accessToken");
                    Number expireIn = (Number) m.get("expireIn");
                    if (StringUtils.isNotBlank(t))
                    {
                        token = t;
                        lifeSec = expireIn == null ? 6900 : expireIn.longValue() - 300;
                        channel = "v1.0/accessToken";
                    }
                    else
                    {
                        // 新版错误响应可能带 code/message（HTTP 200 场景罕见但防御一下）
                        log.warn("[DingTalk] 新版 accessToken 响应无 token 字段：{}", resp);
                    }
                }
            }
            catch (Exception e)
            {
                // 新版接口失败（HTTP 4xx 时 HttpUtils 只读 getInputStream 会抛异常，错误体不可见）
                log.warn("[DingTalk] 新版 accessToken 获取失败，回退旧版 gettoken：{}", e.getMessage());
            }
            // ===== 旧版接口兜底 =====
            if (StringUtils.isBlank(token))
            {
                String url = OAPI + "/gettoken?appkey=" + enc(cfg.getClientId())
                        + "&appsecret=" + enc(cfg.getClientSecret());
                String body = HttpUtils.sendSSLGet(url);
                if (StringUtils.isBlank(body)) throw new DingTalkException("gettoken 返回空（新旧版均失败）");
                @SuppressWarnings("unchecked")
                Map<String, Object> m = JSON.unmarshal(body, Map.class);
                Number errcode = (Number) m.get("errcode");
                if (errcode != null && errcode.intValue() != 0)
                    throw new DingTalkException("gettoken 失败 errcode=" + errcode + " errmsg=" + m.get("errmsg"));
                token = (String) m.get("access_token");
                Number expiresIn = (Number) m.get("expires_in");
                if (StringUtils.isBlank(token)) throw new DingTalkException("gettoken 返回无 access_token");
                lifeSec = expiresIn == null ? 7000 : expiresIn.longValue() - 120;
                channel = "legacy gettoken";
            }
            if (lifeSec < 60) lifeSec = 60;
            this.cachedToken = token;
            this.cachedTokenExpireAt = now + lifeSec * 1000L;
            this.cachedTokenChannel = channel;
            log.info("[DingTalk] access_token 获取成功（{}），有效期 {} 秒", cachedTokenChannel, lifeSec);
            return cachedToken;
        }
        catch (DingTalkException e) { throw e; }
        catch (Exception e)
        {
            throw new DingTalkException("getAccessToken 异常：" + e.getMessage(), e);
        }
    }

    /** 强制清空 access_token 缓存（遇到 token 失效错误时调用后重试） */
    public void clearTokenCache() { cachedToken = null; cachedTokenExpireAt = 0; cachedTokenChannel = ""; }

    // ========================= 手机号 → userid =========================

    public String getUseridByMobile(String mobile) throws DingTalkException
    {
        if (StringUtils.isBlank(mobile)) throw new DingTalkException("手机号为空");
        try
        {
            String token = getAccessToken();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mobile", mobile);
            String url = OAPI + "/topapi/v2/user/getbymobile?access_token=" + enc(token);
            String resp = postJson(url, body);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = JSON.unmarshal(resp, Map.class);
            Number errcode = (Number) m.get("errcode");
            if (errcode != null && errcode.intValue() != 0)
            {
                // 40014 = token 过期，自动重试一次
                if (errcode.intValue() == 40014) { clearTokenCache(); return getUseridByMobile(mobile); }
                throw new DingTalkException("getbymobile 失败 " + m.get("errmsg") + " errcode=" + errcode);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) m.get("result");
            if (result == null) throw new DingTalkException("getbymobile 无 result");
            String uid = (String) result.get("userid");
            if (StringUtils.isBlank(uid)) throw new DingTalkException("该手机号未在企业钉钉中找到对应用户：" + mobile);
            return uid;
        }
        catch (DingTalkException e) { throw e; }
        catch (Exception e) { throw new DingTalkException("getUseridByMobile 异常：" + e.getMessage(), e); }
    }

    // ========================= 高层：按手机号发送 =========================

    /** 按单个手机号发送文本 */
    public DingTalkSendResult sendTextByMobile(String mobile, String text) throws DingTalkException
    {
        return sendTextByMobiles(Collections.singletonList(mobile), text);
    }

    /** 按多个手机号发送同一条文本（若多个手机号对应同一个userid，钉钉只收1次） */
    public DingTalkSendResult sendTextByMobiles(List<String> mobiles, String text) throws DingTalkException
    {
        if (mobiles == null || mobiles.isEmpty()) throw new DingTalkException("手机号列表为空");
        if (StringUtils.isBlank(text)) throw new DingTalkException("消息内容为空");
        // 手机号→userid（有失败的收集起来报告给调用方）
        List<String> userids = mobiles.stream().distinct().filter(StringUtils::isNotBlank)
                .map(m -> {
                    try { return getUseridByMobile(m); }
                    catch (DingTalkException e)
                    { log.warn("[DingTalk] 手机号 {} 查userid失败：{}", m, e.getMessage()); return null; }
                }).filter(StringUtils::isNotBlank).collect(Collectors.toList());
        if (userids.isEmpty()) throw new DingTalkException("所有手机号都未找到钉钉用户，请检查号码是否已加入企业");
        return sendCorpText(String.join(",", userids), text);
    }

    /** Markdown 消息 */
    public DingTalkSendResult sendMarkdownByMobile(String mobile, String title, String markdownText) throws DingTalkException
    {
        if (StringUtils.isBlank(mobile)) throw new DingTalkException("手机号为空");
        String uid = getUseridByMobile(mobile);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("msgtype", "markdown");
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("title", StringUtils.isBlank(title) ? "盘家通知" : title);
        md.put("text", markdownText);
        msg.put("markdown", md);
        return sendCorpMsg(uid, msg);
    }

    // ========================= 底层：发送工作通知 =========================

    /** 发送文本工作通知（入参是逗号分隔 userid 列表） */
    public DingTalkSendResult sendCorpText(String useridList, String text) throws DingTalkException
    {
        if (StringUtils.isBlank(useridList)) throw new DingTalkException("userid_list 为空");
        Map<String, Object> textMap = new LinkedHashMap<>();
        textMap.put("content", text);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("msgtype", "text");
        msg.put("text", textMap);
        return sendCorpMsg(useridList, msg);
    }

    /** 通用发送：msg 由调用方构造（已经含 msgtype + 对应类型体） */
    @SuppressWarnings("unchecked")
    public DingTalkSendResult sendCorpMsg(String useridList, Map<String, Object> msg) throws DingTalkException
    {
        String err = cfg.validate();
        if (err != null) throw new DingTalkException(err);
        try
        {
            for (int attempt = 0; attempt < 2; attempt++)
            {
                String token = getAccessToken();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("agent_id", cfg.getAgentId());
                body.put("userid_list", useridList);
                body.put("msg", msg);
                String url = OAPI + "/topapi/message/corpconversation/asyncsend_v2?access_token=" + enc(token);
                String resp = postJson(url, body);
                Map<String, Object> m = JSON.unmarshal(resp, Map.class);
                Number errcode = (Number) m.get("errcode");
                if (errcode == null || errcode.intValue() == 0)
                {
                    DingTalkSendResult r = new DingTalkSendResult();
                    r.success = true;
                    r.taskId = m.get("task_id") == null ? null : String.valueOf(m.get("task_id"));
                    r.raw = m;
                    log.info("[DingTalk] 工作通知提交成功，task_id={}, 接收userids={}", r.taskId, useridList);
                    return r;
                }
                int ec = errcode.intValue();
                // token 类错误：清缓存后重试 1 次
                if ((ec == 40014 || ec == 40001 || ec == 42001) && attempt == 0)
                {
                    log.warn("[DingTalk] 发送失败（token失效 errcode={}），清理缓存重试", ec);
                    clearTokenCache();
                    continue;
                }
                throw new DingTalkException("工作通知发送失败 errcode=" + ec + " errmsg=" + m.get("errmsg"));
            }
            throw new DingTalkException("工作通知发送失败（重试后仍失败）");
        }
        catch (DingTalkException e) { throw e; }
        catch (Exception e) { throw new DingTalkException("sendCorpMsg 异常：" + e.getMessage(), e); }
    }

    // ========================= 免登 SSO：code → userid → 详情（含手机号） =========================

    /**
     * 钉钉 H5 微应用免登：前端通过 dd.getAuthCode({corpid}) 拿到的 code，交给后端换取当前用户 userid / 昵称等浅信息。
     * <p>注意：此接口不返回手机号，拿手机号需再调用 {@link #getUserDetailByUserid(String)}。
     */
    public DingTalkUser getUserInfoByAuthCode(String authCode) throws DingTalkException
    {
        if (StringUtils.isBlank(authCode)) throw new DingTalkException("authCode 为空");
        try
        {
            for (int attempt = 0; attempt < 2; attempt++)
            {
                String token = getAccessToken();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", authCode);
                String url = OAPI + "/topapi/v2/user/getuserinfo?access_token=" + enc(token);
                String resp = postJson(url, body);
                @SuppressWarnings("unchecked")
                Map<String, Object> m = JSON.unmarshal(resp, Map.class);
                Number errcode = (Number) m.get("errcode");
                if (errcode == null || errcode.intValue() == 0)
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) m.get("result");
                    DingTalkUser u = new DingTalkUser();
                    if (result != null)
                    {
                        u.userid = (String) result.get("userid");
                        u.nick   = (String) result.get("nick");
                        u.deviceId = (String) result.get("device_id");
                    }
                    if (StringUtils.isBlank(u.userid)) throw new DingTalkException("免登返回无 userid，请确认 code 有效性");
                    return u;
                }
                int ec = errcode.intValue();
                if ((ec == 40014 || ec == 40001 || ec == 42001) && attempt == 0)
                { clearTokenCache(); continue; }
                throw new DingTalkException("getuserinfo 失败 errcode=" + ec + " errmsg=" + m.get("errmsg"));
            }
            throw new DingTalkException("getuserinfo 重试后仍失败");
        }
        catch (DingTalkException e) { throw e; }
        catch (Exception e) { throw new DingTalkException("getUserInfoByAuthCode 异常：" + e.getMessage(), e); }
    }

    /** 根据钉钉 userid 读取用户详情（含手机号、姓名、邮箱、头像），需要企业通讯录应用权限 */
    public DingTalkUser getUserDetailByUserid(String userid) throws DingTalkException
    {
        if (StringUtils.isBlank(userid)) throw new DingTalkException("userid 为空");
        try
        {
            for (int attempt = 0; attempt < 2; attempt++)
            {
                String token = getAccessToken();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("userid", userid);
                String url = OAPI + "/topapi/v2/user/get?access_token=" + enc(token);
                String resp = postJson(url, body);
                @SuppressWarnings("unchecked")
                Map<String, Object> m = JSON.unmarshal(resp, Map.class);
                Number errcode = (Number) m.get("errcode");
                if (errcode == null || errcode.intValue() == 0)
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> r = (Map<String, Object>) m.get("result");
                    DingTalkUser u = new DingTalkUser();
                    u.userid = userid;
                    if (r != null)
                    {
                        u.name     = (String) r.get("name");
                        u.mobile   = (String) r.get("mobile");
                        u.email    = (String) r.get("email");
                        u.avatar   = (String) r.get("avatar");
                        u.nick     = StringUtils.isBlank(u.nick) ? u.name : u.nick;
                    }
                    return u;
                }
                int ec = errcode.intValue();
                if ((ec == 40014 || ec == 40001 || ec == 42001) && attempt == 0)
                { clearTokenCache(); continue; }
                throw new DingTalkException("user/get 失败 errcode=" + ec + " errmsg=" + m.get("errmsg"));
            }
            throw new DingTalkException("user/get 重试后仍失败");
        }
        catch (DingTalkException e) { throw e; }
        catch (Exception e) { throw new DingTalkException("getUserDetailByUserid 异常：" + e.getMessage(), e); }
    }

    /** 高层：一步完成 code → userid + 详情。返回的 DingTalkUser 必含 userid，其他字段（mobile、name）视钉钉接口权限可能为空 */
    public DingTalkUser resolveByAuthCode(String authCode) throws DingTalkException
    {
        DingTalkUser lite = getUserInfoByAuthCode(authCode);
        try
        {
            DingTalkUser detail = getUserDetailByUserid(lite.userid);
            detail.userid = lite.userid; // 兜底
            if (StringUtils.isBlank(detail.nick)) detail.nick = lite.nick;
            if (StringUtils.isBlank(detail.deviceId)) detail.deviceId = lite.deviceId;
            return detail;
        }
        catch (DingTalkException e)
        {
            // 读取详情失败（比如应用没开通通讯录读权限）至少返回浅信息（有 userid），让调用方自行兜底
            log.warn("[DingTalk] resolveByAuthCode 读取详情失败（可能是通讯录权限未开）：{}，仅返回浅信息 userid={}", e.getMessage(), lite.userid);
            return lite;
        }
    }

    // ========================= 免登用户对象 =========================

    public static class DingTalkUser
    {
        public String userid;     // 钉钉 userid（必返）
        public String nick;       // 昵称（浅信息返）
        public String deviceId;   // 设备ID（浅信息返）
        public String name;       // 姓名（详情返）
        public String mobile;     // 手机号（详情返，匹配系统用户的核心字段）
        public String email;      // 邮箱（详情返）
        public String avatar;     // 头像（详情返）

        // ===== 同步时使用的扩展字段（user/list 接口返回）=====
        public boolean active;              // 是否在职（false=离职，同步时把本地用户禁用）
        public boolean leader;              // 是否为"部门主负责人/主管"
        public java.util.List<Long> deptIdList; // 所属全部钉钉部门ID集合

        public String getUserid() { return userid; }
        public String getName()   { return StringUtils.isBlank(name) ? nick : name; }
        public String getMobile() { return mobile; }
        public String getEmail()  { return email; }
    }

    // ========================= 钉钉部门对象 =========================

    public static class DingTalkDept
    {
        public Long deptId;          // 钉钉部门ID
        public Long parentId;        // 父部门ID（钉钉侧）；根=1
        public String name;          // 部门名称
        public Integer order;        // 排序号
        public String deptManagerUseridList; // 主管 userid 列表，逗号分隔
        public Boolean createDeptGroup;
        public Boolean autoAddUser;
        public int depth;            // 计算属性：树深度（同步落库按 depth 从小到大 INSERT）

        public Long getDeptId() { return deptId; }
        public String getName() { return name; }
    }

    // ========================= 组织架构：部门递归 + 用户分页 =========================

    /**
     * 递归获取所有部门（从 dept_id=1 根部门开始）。
     * 返回的列表已按 depth 升序排序（先根后子），可直接按顺序 INSERT。
     */
    public java.util.List<DingTalkDept> listAllDepartments() throws DingTalkException
    {
        String err = cfg.validate();
        if (err != null) throw new DingTalkException(err);
        java.util.List<DingTalkDept> all = new java.util.ArrayList<>();
        // 钉钉根部门固定为 1
        recurseDepartments(1L, 0, all);
        all.sort((a, b) -> Integer.compare(a.depth, b.depth));
        log.info("[DingTalk] 全量部门拉取完毕，共 {} 个", all.size());
        return all;
    }

    private void recurseDepartments(Long dtParentId, int depth, java.util.List<DingTalkDept> out) throws DingTalkException
    {
        try
        {
            for (int attempt = 0; attempt < 2; attempt++)
            {
                String token = getAccessToken();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("dept_id", dtParentId);
                String url = OAPI + "/topapi/v2/department/listsub?access_token=" + enc(token);
                String resp = postJson(url, body);
                @SuppressWarnings("unchecked")
                Map<String, Object> m = JSON.unmarshal(resp, Map.class);
                Number errcode = (Number) m.get("errcode");
                if (errcode == null || errcode.intValue() == 0)
                {
                    @SuppressWarnings("unchecked")
                    java.util.List<Map<String, Object>> result =
                            (java.util.List<Map<String, Object>>) m.get("result");
                    if (result == null) return;
                    for (Map<String, Object> row : result)
                    {
                        DingTalkDept d = new DingTalkDept();
                        d.deptId = toLong(row.get("dept_id"));
                        d.parentId = toLong(row.get("parent_id"));
                        d.name = (String) row.get("name");
                        d.order = toInteger(row.get("order"));
                        d.deptManagerUseridList = (String) row.get("dept_manager_userid_list");
                        d.createDeptGroup = (Boolean) row.get("create_dept_group");
                        d.autoAddUser = (Boolean) row.get("auto_add_user");
                        d.depth = depth + 1;
                        if (d.deptId == null) continue;
                        out.add(d);
                        // 递归子部门
                        recurseDepartments(d.deptId, depth + 1, out);
                    }
                    return;
                }
                int ec = errcode.intValue();
                if ((ec == 40014 || ec == 40001 || ec == 42001) && attempt == 0)
                { clearTokenCache(); continue; }
                throw new DingTalkException("department/listsub 失败 errcode=" + ec + " errmsg=" + m.get("errmsg"));
            }
        }
        catch (DingTalkException e) { throw e; }
        catch (Exception e) { throw new DingTalkException("recurseDepartments 异常：" + e.getMessage(), e); }
    }

    /**
     * 分页拉取指定部门下的用户（含离职/停号通过参数控制，默认未离职）。
     * 返回的 DingTalkUser 里多了字段：deptIdList(所属部门列表)、leader(是否主管)、active/状态。
     */
    @SuppressWarnings("unchecked")
    public UserPage listUsersByDeptId(Long dtDeptId, long cursor, int pageSize) throws DingTalkException
    {
        if (dtDeptId == null) throw new DingTalkException("dtDeptId 为空");
        if (pageSize <= 0 || pageSize > 100) pageSize = 50;
        try
        {
            for (int attempt = 0; attempt < 2; attempt++)
            {
                String token = getAccessToken();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("dept_id", dtDeptId);
                body.put("cursor", cursor);
                body.put("size", pageSize);
                body.put("order_field", "modify_desc");
                body.put("contain_access_limit", false);
                String url = OAPI + "/topapi/v2/user/list?access_token=" + enc(token);
                String resp = postJson(url, body);
                Map<String, Object> m = JSON.unmarshal(resp, Map.class);
                Number errcode = (Number) m.get("errcode");
                if (errcode == null || errcode.intValue() == 0)
                {
                    Map<String, Object> result = (Map<String, Object>) m.get("result");
                    UserPage page = new UserPage();
                    if (result != null)
                    {
                        page.hasNext = Boolean.TRUE.equals(result.get("has_more"));
                        Object nextCursor = result.get("next_cursor");
                        page.nextCursor = nextCursor == null ? cursor + 1 : Long.parseLong(String.valueOf(nextCursor));
                        java.util.List<Map<String, Object>> list = (java.util.List<Map<String, Object>>) result.get("list");
                        if (list != null)
                        {
                            for (Map<String, Object> r : list)
                            {
                                DingTalkUser u = new DingTalkUser();
                                u.userid = (String) r.get("userid");
                                u.name   = (String) r.get("name");
                                u.nick   = (String) r.get("nick");
                                u.mobile = (String) r.get("mobile");
                                u.email  = (String) r.get("email");
                                u.avatar = (String) r.get("avatar");
                                u.active = !Boolean.FALSE.equals(r.get("active")); // 非 false 视为在职
                                try
                                {
                                    Object leader = r.get("leader");
                                    if (leader != null) u.leader = "1".equals(String.valueOf(leader)) || Boolean.TRUE.equals(leader);
                                } catch (Exception ignore) { /* ignore */ }
                                try
                                {
                                    Object depIds = r.get("dept_id_list");
                                    if (depIds instanceof java.util.List)
                                    {
                                        java.util.List<Long> ids = new java.util.ArrayList<>();
                                        for (Object o : (java.util.List<Object>) depIds) ids.add(Long.parseLong(String.valueOf(o)));
                                        u.deptIdList = ids;
                                    }
                                } catch (Exception ignore) { /* ignore */ }
                                page.users.add(u);
                            }
                        }
                    }
                    return page;
                }
                int ec = errcode.intValue();
                if ((ec == 40014 || ec == 40001 || ec == 42001) && attempt == 0)
                { clearTokenCache(); continue; }
                throw new DingTalkException("user/list 失败 errcode=" + ec + " errmsg=" + m.get("errmsg"));
            }
            throw new DingTalkException("user/list 重试后仍失败");
        }
        catch (DingTalkException e) { throw e; }
        catch (Exception e) { throw new DingTalkException("listUsersByDeptId 异常：" + e.getMessage(), e); }
    }

    /** 高层：拉取全部部门下的用户，跨部门去重。返回的 user.deptIdList 为合并后的全部部门集合 */
    public java.util.Map<String, DingTalkUser> listAllUsers() throws DingTalkException
    {
        java.util.List<DingTalkDept> depts = listAllDepartments();
        // 额外加根部门 1（listAllDepartments 只返回根的子，根本身通常不存人员，但防止意外）
        java.util.Set<Long> deptIds = new java.util.LinkedHashSet<>();
        deptIds.add(1L);
        for (DingTalkDept d : depts) deptIds.add(d.deptId);

        java.util.Map<String, DingTalkUser> users = new java.util.LinkedHashMap<>();
        int pageSize = 50;
        for (Long deptId : deptIds)
        {
            long cursor = 0;
            while (true)
            {
                UserPage page = listUsersByDeptId(deptId, cursor, pageSize);
                for (DingTalkUser u : page.users)
                {
                    if (StringUtils.isBlank(u.userid)) continue;
                    DingTalkUser merged = users.get(u.userid);
                    if (merged == null)
                    {
                        merged = u;
                        if (merged.deptIdList == null) merged.deptIdList = new java.util.ArrayList<>();
                        users.put(u.userid, merged);
                    }
                    else
                    {
                        // 合并部门归属
                        if (merged.deptIdList == null) merged.deptIdList = new java.util.ArrayList<>();
                        if (u.deptIdList != null) for (Long id : u.deptIdList)
                        { if (!merged.deptIdList.contains(id)) merged.deptIdList.add(id); }
                        if (u.leader) merged.leader = true;
                        if (StringUtils.isBlank(merged.mobile) && StringUtils.isNotBlank(u.mobile)) merged.mobile = u.mobile;
                        if (StringUtils.isBlank(merged.email)  && StringUtils.isNotBlank(u.email))  merged.email  = u.email;
                    }
                    // 保证当前部门也在列表（避免 dept_id_list 拉不到的情况）
                    if (!merged.deptIdList.contains(deptId)) merged.deptIdList.add(deptId);
                }
                if (!page.hasNext) break;
                cursor = page.nextCursor;
            }
        }
        log.info("[DingTalk] 全量用户拉取完毕，共 {} 人（跨部门已去重）", users.size());
        return users;
    }

    /** 用户分页对象 */
    public static class UserPage
    {
        public boolean hasNext;
        public long nextCursor;
        public java.util.List<DingTalkUser> users = new java.util.ArrayList<>();
    }

    // ========================= 辅助 =========================

    private static Long toLong(Object o)
    {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); }
        catch (Exception e) { return null; }
    }
    private static Integer toInteger(Object o)
    {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); }
        catch (Exception e) { return null; }
    }

    private String postJson(String url, Map<String, Object> body) throws Exception
    {
        String json = JSON.marshal(body);
        log.debug("[DingTalk] POST {} body={}", url, json.length() > 300 ? json.substring(0, 300) + "..." : json);
        String resp = HttpUtils.sendSSLPost(url, json, MediaType.APPLICATION_JSON_VALUE);
        log.debug("[DingTalk] RESP len={}", resp == null ? 0 : resp.length());
        return resp;
    }

    private static String enc(String s)
    {
        if (s == null) return "";
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { return s; }
    }

    // ========================= 自定义异常 & 返回值 =========================

    public static class DingTalkException extends Exception
    {
        private static final long serialVersionUID = 1L;
        public DingTalkException(String msg) { super(msg); }
        public DingTalkException(String msg, Throwable t) { super(msg, t); }
    }

    public static class DingTalkSendResult
    {
        public boolean success;
        public String  taskId;
        public Map<String, Object> raw;
        public boolean isSuccess() { return success; }
        public String getTaskId() { return taskId; }
    }
}
