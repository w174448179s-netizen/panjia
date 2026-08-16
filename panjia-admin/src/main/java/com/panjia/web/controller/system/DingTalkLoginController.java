package com.panjia.web.controller.system;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.panjia.common.annotation.Anonymous;
import com.panjia.common.constant.ShiroConstants;
import com.panjia.common.core.controller.BaseController;
import com.panjia.common.core.domain.AjaxResult;
import com.panjia.common.core.domain.entity.SysUser;
import com.panjia.common.utils.ShiroUtils;
import com.panjia.common.utils.StringUtils;
import com.panjia.framework.dingtalk.DingTalkConfig;
import com.panjia.framework.dingtalk.DingTalkNoticeService;
import com.panjia.framework.dingtalk.DingTalkNoticeService.DingTalkException;
import com.panjia.framework.dingtalk.DingTalkNoticeService.DingTalkUser;
import com.panjia.framework.shiro.token.DingTalkSsoToken;
import com.panjia.system.service.ISysUserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ui.ModelMap;

/**
 * 钉钉免登入口控制器
 * <p>
 * 钉钉工作台点击 H5 应用时，钉钉**不会**自动在 URL 上追加 authCode，必须通过 JSAPI 主动获取。
 * 因此推荐把钉钉控制台「应用首页地址」配置为 「https://域名/dingtalk/sso」，
 * 由 /dingtalk/sso 页面加载钉钉 JSAPI SDK，调用 dd.runtime.permission.requestAuthCode 拿到 authCode 后，
 * 再通过 POST 表单跳转到 /dingtalk/login（防反代吞 query）完成后端免登。
 * <ul>
 *   <li>入口：钉钉控制台 → 应用首页地址填 「https://域名/dingtalk/sso」
 *       <br>/dingtalk/sso 页面加载 JSAPI → dd.runtime.permission.requestAuthCode（corpId 来自 sys_config.dingtalk.corp_id，
 *       新版 SDK 不再在 dd 对象上自动注入，必须后端读取后注入前端）→
 *       拿到 authCode → POST 表单提交 code/state 到 /dingtalk/login</li>
 *   <li>/dingtalk/login（GET/POST）：收到 code → 调钉钉 /topapi/v2/user/getuserinfo 换 userid → 再调 /topapi/v2/user/get 补全手机号</li>
 * </ul>
 * 后端流程：
 *   1) GET /dingtalk/login?code=authCode → 调钉钉 topapi/v2/user/getuserinfo（企业 access_token + authCode）换 userid
 *   2) 调 topapi/v2/user/get（access_token + userid）补全手机号、姓名（需「成员信息读权限」qyapi_get_member）
 *   3) 优先按 dingtalk_userid（绑定过更快）→ 再按手机号 → 兜底邮箱 → 匹配本地 sys_user
 *   4) 匹配成功：DingTalkSsoToken → Shiro 免密登录 → 302 到 /index
 *   5) 未匹配：302 到 /login?dt=no_bind&mobile=xxx&userid=xxx → 登录页提示联系管理员开户 / 先同步组织
 * <p>
 * 附带调试接口（匿名只读非敏感，便于现场核对参数）：
 *   - POST /dingtalk/config → 展示 enabled/appId/agentId/clientId 和 access_token 获取是否正常
 *
 * @author panjia
 */
@Controller
@RequestMapping("/dingtalk")
public class DingTalkLoginController extends BaseController
{
    private static final Logger log = LoggerFactory.getLogger(DingTalkLoginController.class);

    @Autowired
    private DingTalkNoticeService dingTalk;

    @Autowired
    private DingTalkConfig dingTalkCfg;

    @Autowired
    private ISysUserService userService;

    @Autowired(required = false)
    private DataSource dataSource;

    // ================== 入口：GET（query?code=xxx）与 POST（form body code=xxx）双支持 ==================
    // GET 用于历史 URL 直带参数场景；POST 用于「sso 页面提交表单」场景（防反代吞 query）

    @Anonymous
    @GetMapping("/login")
    public String dingtalkLoginGet(
            @RequestParam(value = "code",  required = false) String authCode,
            @RequestParam(value = "state", required = false) String state,
            HttpServletRequest request,
            HttpServletResponse response,
            ModelMap mmap) throws IOException
    {
        return dingtalkLoginImpl(authCode, state, request, response, mmap, "GET");
    }

    @Anonymous
    @PostMapping("/login")
    public String dingtalkLoginPost(
            @RequestParam(value = "code",  required = false) String authCode,
            @RequestParam(value = "state", required = false) String state,
            HttpServletRequest request,
            HttpServletResponse response,
            ModelMap mmap) throws IOException
    {
        return dingtalkLoginImpl(authCode, state, request, response, mmap, "POST");
    }

    private String dingtalkLoginImpl(
            String authCode,
            String state,
            HttpServletRequest request,
            HttpServletResponse response,
            ModelMap mmap,
            String method) throws IOException
    {
        // ===== 诊断日志：每次进入都打全请求 URL / query / 参数名 / 关键头，便于排查"code 为空" =====
        String fullUrl = request.getRequestURL().toString()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        java.util.Enumeration<String> pnames = request.getParameterNames();
        java.util.List<String> pnameList = new java.util.ArrayList<>();
        while (pnames.hasMoreElements()) pnameList.add(pnames.nextElement());
        log.info("[DingTalk免登] 收到请求 method={} fullUrl={} | parameterMap.keys={} | code={} | state={} | referer={} | ua={} | xff={} | xfProto={}",
                method, fullUrl, pnameList,
                authCode == null ? "(null)" : (authCode.isEmpty() ? "(empty)" : authCode.substring(0, Math.min(8, authCode.length())) + "..."),
                state == null ? "(null)" : state,
                nvl(request.getHeader("referer")),
                nvl(request.getHeader("user-agent")),
                nvl(request.getHeader("x-forwarded-for")),
                nvl(request.getHeader("x-forwarded-proto")));

        String errCode = "";
        String info = "";

        // 1) 前置校验
        if (!dingTalkCfg.isEnabled())
        {
            errCode = "disabled"; info = "管理员未启用钉钉通知配置";
        }
        else if (StringUtils.isBlank(authCode))
        {
            // 没带 code：要么是直接 GET /dingtalk/login 自测 → 跳回登录页提示
            errCode = "no_code"; info = "未接收到钉钉免登授权码 code，请从钉钉工作台应用入口进入（应用首页地址需配置为：https://域名/dingtalk/sso）。"
                    + "若从钉钉工作台点击仍无 code，请检查钉钉控制台「应用首页地址」是否为 /dingtalk/sso，以及应用是否已发布；"
                    + "当前请求 method=" + method + "，若为 POST 且无 code 通常说明前端 requestAuthCode 失败后走了兜底表单提交，请查看 [DingTalkSSO-Trace] 诊断日志定位 JSAPI 失败原因。";
            log.warn("[DingTalk免登] 进入 /dingtalk/login 但 code 为空。method={} fullUrl={} params={} referer={} "
                    + "→ 典型原因：1) 直接在浏览器粘贴地址测试（钉钉不会注入code）；2) 钉钉控制台首页地址非 /dingtalk/sso；"
                    + "3) sso 页面 JSAPI requestAuthCode 失败走了兜底（见 [DingTalkSSO-Trace] 日志）",
                    method, fullUrl, pnameList, nvl(request.getHeader("referer")));
        }

        if (StringUtils.isNotBlank(errCode))
        {
            return "redirect:/login?dt_err=" + errCode + "&info=" + enc(info);
        }

        try
        {
            // 2) code → 钉钉用户信息（userid / mobile / name）
            DingTalkUser dtUser = dingTalk.resolveByAuthCode(authCode);
            log.info("[DingTalk免登] code解析成功，userid={} mobile={} name={}",
                    dtUser.userid, mask(dtUser.mobile), dtUser.getName());

            // 3) 映射系统用户：先按 dingtalk_userid（绑定过的更快）→ 再按手机号 → 都没有则失败
            SysUser sys = findSysUser(dtUser);
            if (sys == null)
            {
                String mob = StringUtils.isBlank(dtUser.getMobile()) ? "无" : mask(dtUser.getMobile());
                log.warn("[DingTalk免登] 未匹配到本地系统用户，userid={} mobile={}", dtUser.userid, mob);
                return "redirect:/login?dt=no_bind&userid=" + enc(dtUser.userid) + "&mobile=" + enc(dtUser.getMobile());
            }

            // 4) dingtalk_userid 首次绑定到 sys_user（以后直接按 userid 查，不再依赖手机号）
            bindDingtalkUseridIfNeeded(sys.getUserId(), dtUser.userid);

            // 5) Shiro 免密登录（DingTalkSsoToken 分支）
            // 【注意】不要在这里显式 subject.logout()！
            // 场景：带着上次的活会话再次免登（第二次打开应用）。logout 会立刻删除旧 session，
            // 但本请求的 filter 链收尾阶段仍持有请求开始时绑定的旧 sessionId，回头一读发现已删
            // → 抛 "Could not find session with ID [旧id]" → 把已登录成功的响应覆盖成 500。
            // subject.login() 自身会作废旧会话并绑定新 subject，无需手动 logout。
            Subject subject = SecurityUtils.getSubject();
            subject.login(new DingTalkSsoToken(sys.getLoginName()));

            // 5.1) 登录成功后，强制刷新当前 subject 的 session：
            //  DefaultSecurityManager.login() 会调用 changeSessionId/createSession 生成新 sessionId，
            //  但 Spring MVC 返回 "redirect:/index" 时 Servlet 容器 commit response，Shiro Web 的
            //  Session 管理 Cookie 写入依赖 filter 链执行完；anon 链下 session 过滤器有时不会
            //  触发完整的 cookie 写入流程，导致 302 下一次请求浏览器仍携带旧 sessionId → 查不到直接 UnknownSession。
            //  这里主动 touch + setAttribute 让 Shiro 在当前请求立刻标记 session 为 dirty、
            //  触发 DefaultWebSessionManager storeSessionId，把新 JSESSIONID cookie 写回 response。
            Session newSession = subject.getSession();
            try
            {
                newSession.touch();
                newSession.setAttribute(ShiroConstants.CURRENT_USERNAME, sys.getLoginName());
                // 顺便把 dingtalk userid 也存进 session，便于后续调钉钉接口时直接取
                newSession.setAttribute("dt_userid", dtUser.getUserid());
            }
            catch (Exception ignore) { /* touch/setAttr 失败不影响登录成功后的跳转 */ }

            String newSessionId = newSession != null ? String.valueOf(newSession.getId()) : "-";
            log.info("[DingTalk免登] 登录成功 userId={} loginName={} sessionId={} roles={}",
                    sys.getUserId(), sys.getLoginName(), newSessionId,
                    sys.getRoles() == null ? "" : sys.getRoles().stream().map(r -> r.getRoleName()).toList());

            // ===== 5.2) Cookie 说明：不再手动 addHeader 写第二份 JSESSIONID =====
            // ShiroConfig 的 sessionIdCookie 模板已配置 SameSite=Lax + Secure + Path=/，
            // 由 SessionManager 统一写出（单一来源）。之前这里手动 addHeader 追加的第二份
            // 同名 Set-Cookie 会与 Shiro 写的相互覆盖，Safari/WKWebView 拿到无 SameSite 的那份
            // 后丢弃，导致登录后 1~2 分钟出现 UnknownSessionException（session 实际没过期）。

            // 6) 【关键】直接把 dtok 中转页作为本 POST 的 200 响应返回（不再 302）
            //    为什么不用 redirect:/dingtalk/dtok？
            //    实测钉钉 PC WKWebView 对「表单 POST → 302 Location」组合不可靠：
            //    302 已发出（日志有登录成功），但 WebView 不跟随跳转、也不发任何后续请求，
            //    直接报 NSURLErrorNetworkConnectionLost（错误页挂入口 /dingtalk/sso 上，有误导性）。
            //    直接渲染 200 页面则稳定：dtok.html <1KB 无外部依赖，onload 后 JS 再跳 /index，
            //    此时属于已加载页面的 JS 行为，不受 WKWebView 导航超时监控。
            String target = StringUtils.isBlank(state) ? "/index" : safeRedirectPath(state);
            mmap.put("go", target);
            log.info("[DingTalk免登] 登录成功，直接渲染 dtok 中转页（无302），go={}", target);
            return "dingtalk/dtok";
        }
        catch (DingTalkException e)
        {
            log.warn("[DingTalk免登] 钉钉接口返回异常：{}", e.getMessage());
            return "redirect:/login?dt_err=api&info=" + enc(e.getMessage());
        }
        catch (org.apache.shiro.authc.AuthenticationException e)
        {
            log.warn("[DingTalk免登] Shiro 认证失败：{}", e.getMessage());
            return "redirect:/login?dt_err=shiro&info=" + enc(e.getMessage());
        }
        catch (Exception e)
        {
            log.error("[DingTalk免登] 未知异常", e);
            return "redirect:/login?dt_err=unknown&info=" + enc(e.getMessage() == null ? "服务异常" : e.getMessage());
        }
    }

    // ================== 退出：清理本地 Shiro Session（钉钉会话仍保持） ==================

    @Anonymous
    @GetMapping("/logout")
    public String dingtalkLogout()
    {
        try { ShiroUtils.getSubject().logout(); } catch (Exception ignore) { /* ignore */ }
        return "redirect:/login?dt=logout";
    }

    // ================== 诊断：前端 sso 页把 JSAPI 各阶段日志通过 sendBeacon 回传这里 ==================

    @Anonymous
    @PostMapping("/log")
    @ResponseBody
    public AjaxResult traceLog(
            @RequestParam(value = "step",  required = false) String step,
            @RequestParam(value = "corpId",required = false) String corpId,
            @RequestParam(value = "codeLen", required = false) String codeLen,
            @RequestParam(value = "codePrefix", required = false) String codePrefix,
            @RequestParam(value = "err", required = false) String err,
            @RequestParam(value = "ua",  required = false) String ua,
            @RequestParam(value = "href",required = false) String href,
            HttpServletRequest request)
    {
        String ip = StringUtils.isBlank(request.getHeader("x-forwarded-for")) ? request.getRemoteAddr() : request.getHeader("x-forwarded-for");
        log.info("[DingTalkSSO-Trace] step={} corpId={} codeLen={} codePrefix={} ua={} href={} clientIp={} err={}",
                nvl(step), maskCorp(corpId), nvl(codeLen), nvl(codePrefix),
                nvl(ua == null ? request.getHeader("user-agent") : ua),
                maskHref(href), ip, nvl(err));
        return success();
    }

    private String maskCorp(String s){ if (StringUtils.isBlank(s)) return s; if (s.length() <= 6) return "***"; return s.substring(0,3) + "***" + s.substring(s.length()-3); }
    private String maskHref(String s){ if (StringUtils.isBlank(s)) return s; int q = s.indexOf('?'); if (q < 0) return s; return s.substring(0, q) + "?***"; }

    // ================== 调试接口：只读展示非敏感配置，便于现场核对参数（匿名访问，便于钉钉手机端自测） ==================

    @Anonymous
    @PostMapping("/config")
    @ResponseBody
    public AjaxResult debugConfig()
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled",       dingTalkCfg.isEnabled());
        m.put("appId",         nvl(dingTalkCfg.getAppId()));
        m.put("agentId",       nvl(dingTalkCfg.getAgentIdStr()));
        m.put("clientId",      nvl(dingTalkCfg.getClientId()));
        m.put("clientSecret",  StringUtils.isBlank(dingTalkCfg.getClientSecret()) ? "" : "******");
        // 尝试拿一次 token，校验是否配置正确
        try {
            String token = dingTalk.getAccessToken();
            m.put("tokenOk", true);
            m.put("tokenLen", token == null ? 0 : token.length());
            m.put("tokenHint", token == null ? "" : token.substring(0, Math.min(6, token.length())) + "...");
        } catch (Exception e) {
            m.put("tokenOk", false);
            m.put("tokenErr", e.getMessage());
        }
        return success(m);
    }

    // ================== 入口 B：sso 中转页（JSAPI 取 authCode → POST form 到 /dingtalk/login） ==================

    @Anonymous
    @GetMapping("/sso")
    public String ssoPage(HttpServletRequest request, ModelMap mmap)
    {
        // ===== 已登录快检：带有效会话再次进入时直接去 /index，跳过整个免登流程 =====
        // 钉钉工作台每次点应用都固定打开本页（/dingtalk/sso），不感知登录态；
        // 无此快检时每次点击都完整走一遍 JSAPI 取码+换码+建会话（1-2秒 + 一次钉钉API调用）。
        // 会话失效/无 Cookie 时正常落入下方免登流程，行为不变。
        try
        {
            Subject subject = SecurityUtils.getSubject();
            if (subject != null && subject.isAuthenticated())
            {
                log.info("[DingTalkSSO] 已有有效会话（principal={}），跳过免登直达首页", subject.getPrincipal());
                mmap.put("go", "/index");
                return "dingtalk/dtok";
            }
        }
        catch (Exception ignore) { /* 会话异常时按未登录处理，走正常免登 */ }

        mmap.put("redirect", request.getContextPath() + "/dingtalk/login");
        mmap.put("traceUrl", request.getContextPath() + "/dingtalk/log");
        // JSAPI dd.runtime.permission.requestAuthCode 要求传 corpId；新版钉钉 SDK 不再自动注入到 dd.corpId，
        // 必须从 sys_config 读 dingtalk.corp_id 注入页面；若仍为空，则在 trace 日志中给出明确提示
        String corpId = dingTalkCfg.getCorpId();
        mmap.put("corpId", corpId == null ? "" : corpId);
        log.info("[DingTalkSSO] sso 页面初始化 corpId={} (len={})",
                corpId == null || corpId.isEmpty() ? "(空，请配置 dingtalk.corp_id)" : corpId.substring(0, 3) + "***",
                corpId == null ? 0 : corpId.length());
        return "dingtalk/sso";
    }

    // ================== 入口 C：登录成功中转页（极轻量 → 再异步跳首页，规避 WKWebView 超时） ==================

    @Anonymous
    @GetMapping("/dtok")
    public String dtokPage(
            @RequestParam(value = "go", required = false) String go,
            HttpServletRequest request,
            ModelMap mmap)
    {
        // 只允许站内路径（以 / 开头 且 不包含 // 协议跳转），防止开放重定向漏洞
        String safeGo = safeRedirectPath(go);
        mmap.put("go", safeGo);
        log.info("[DingTalkDTok] 中转页加载，go={}", safeGo);
        return "dingtalk/dtok";
    }

    // ================== 辅助：用户映射 + dingtalk_userid 绑定（JDBC 原生） ==================

    /** 优先按 dingtalk_userid 查，再按手机号查 */
    private SysUser findSysUser(DingTalkUser dt)
    {
        // 1) 按已绑定的 dingtalk_userid
        if (StringUtils.isNotBlank(dt.userid))
        {
            Long uid = queryUserIdByDingtalkUserid(dt.userid);
            if (uid != null)
            {
                SysUser u = userService.selectUserById(uid);
                if (u != null) return u;
            }
        }
        // 2) 按手机号
        if (StringUtils.isNotBlank(dt.getMobile()))
        {
            SysUser u = userService.selectUserByPhoneNumber(dt.getMobile());
            if (u != null) return u;
        }
        // 3) 按邮箱兜底
        if (StringUtils.isNotBlank(dt.getEmail()))
        {
            SysUser u = userService.selectUserByEmail(dt.getEmail());
            if (u != null) return u;
        }
        return null;
    }

    private Long queryUserIdByDingtalkUserid(String dtUserid)
    {
        if (dataSource == null || StringUtils.isBlank(dtUserid)) return null;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id FROM sys_user WHERE dingtalk_userid = ? AND del_flag = '0' LIMIT 1"))
        {
            ps.setString(1, dtUserid);
            try (ResultSet rs = ps.executeQuery())
            {
                if (rs.next()) return rs.getLong(1);
            }
        }
        catch (SQLException e)
        {
            // 列不存在（未执行 ALTER SQL）→ 降级：静默忽略错误，继续走手机号匹配
            if (!e.getMessage().toLowerCase().contains("dingtalk_userid"))
            {
                log.warn("[DingTalk] queryUserIdByDingtalkUserid 查询失败（列可能未加，请执行升级SQL）：{}", e.getMessage());
            }
        }
        return null;
    }

    private void bindDingtalkUseridIfNeeded(Long userId, String dtUserid)
    {
        if (dataSource == null || userId == null || StringUtils.isBlank(dtUserid)) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE sys_user SET dingtalk_userid = ?, update_by = ?, update_time = now() " +
                     "WHERE user_id = ? AND (dingtalk_userid IS NULL OR dingtalk_userid <> ?)"))
        {
            ps.setString(1, dtUserid);
            ps.setString(2, currentLoginName());
            ps.setLong(3, userId);
            ps.setString(4, dtUserid);
            int n = ps.executeUpdate();
            if (n > 0) log.info("[DingTalk] user_id={} 已绑定 dingtalk_userid={}", userId, dtUserid);
        }
        catch (SQLException e)
        {
            if (e.getMessage().toLowerCase().contains("dingtalk_userid"))
            {
                log.warn("[DingTalk] sys_user.dingtalk_userid 列未创建，跳过绑定（请执行 sql/panjia-dingtalk-sso-upgrade.sql）");
            }
            else
            {
                log.warn("[DingTalk] bindDingtalkUseridIfNeeded 更新失败：{}", e.getMessage());
            }
        }
    }

    private static String nvl(String s) { return s == null ? "" : s; }
    private static String mask(String s)
    {
        if (StringUtils.isBlank(s)) return s;
        if (s.length() <= 4) return "****";
        return s.substring(0, 3) + "****" + s.substring(s.length() - 4);
    }
    private static String enc(String s)
    {
        if (s == null) return "";
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { return s; }
    }
    private static String safeRedirect(String state)
    {
        return "redirect:" + safeRedirectPath(state);
    }

    /** 与 safeRedirect 逻辑相同，但只返回纯路径（不含 "redirect:" 前缀），用于 dtok 中转页的 go 参数透传 */
    private static String safeRedirectPath(String state)
    {
        if (StringUtils.isBlank(state)) return "/index";
        if (state.startsWith("/") && !state.startsWith("//")) return state;
        return "/index";
    }

    /** 获取当前登录人 loginName，无登录态或异常则返回 "dingtalk" */
    private static String currentLoginName()
    {
        try
        {
            Object principal = ShiroUtils.getSubject().getPrincipal();
            if (principal == null) return "dingtalk";
            if (principal instanceof SysUser) return ((SysUser) principal).getLoginName();
            return principal.toString();
        }
        catch (Exception e)
        {
            return "dingtalk";
        }
    }
}
