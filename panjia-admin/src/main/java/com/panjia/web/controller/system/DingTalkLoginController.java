package com.panjia.web.controller.system;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

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

import com.panjia.common.annotation.Anonymous;
import com.panjia.common.constant.ShiroConstants;
import com.panjia.common.core.controller.BaseController;
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
 * 钉钉 H5 微应用免登控制器
 * <p>
 * 钉钉工作台点击应用时固定打开「应用首页地址」（建议配置为 /dingtalk/sso），不会自动附带 authCode，
 * 必须由页面加载 JSAPI 主动获取。本控制器实现完整免登链路：
 * <ul>
 *   <li>GET /dingtalk/sso：入口页。带有效会话 → 直接渲染 dtok 跳 /index（已登录快检）；
 *       否则渲染 sso 页（corpId 由 sys_config.dingtalk.corp_id 服务端注入，新版 SDK 不再自动注入 dd.corpId），
 *       由前端 JSAPI requestAuthCode 取 authCode 后 POST 表单到 /dingtalk/login</li>
 *   <li>POST /dingtalk/login：code → 钉钉 user/getuserinfo 换 userid → user/get 补全手机号 →
 *       按 dingtalk_userid/手机号/邮箱匹配本地用户 → Shiro 免密登录 → <b>直接渲染 dtok 中转页（200，不发 302，
 *       钉钉 WKWebView 对导航 302 跟随不可靠）</b> → 前端 JS 跳 /index</li>
 * </ul>
 * 关键约束（历史排障结论，勿回退）：
 * <ul>
 *   <li>登录成功路径禁用 302（WebView 不跟随，报 NSURLErrorNetworkConnectionLost），统一直渲染</li>
 *   <li>不显式 subject.logout()（同请求收尾阶段回读旧 session 会 500）</li>
 *   <li>Cookie 由 ShiroConfig 的 sessionIdCookie 模板统一写出（SameSite=Lax + Secure），单一来源</li>
 * </ul>
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
        // ===== 轻量防滥用：单 IP 60 秒内最多 10 次免登尝试（含空 code），超出直接 429 =====
        // 此接口是仅存的匿名业务口，防外部扫描器刷请求（每次空 code 都要打日志+302）和暴力探测
        if (!rateLimitAllow(request))
        {
            log.warn("[DingTalk免登] IP 请求过于频繁，已限流：{}", clientIp(request));
            response.sendError(429, "请求过于频繁，请稍后再试");
            return null;
        }

        // ===== 简要诊断日志：method/参数名/code前缀/referer（排障够用，避免每请求打全量头） =====
        String fullUrl = request.getRequestURL().toString()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        log.info("[DingTalk免登] 收到请求 method={} fullUrl={} | code={} | state={} | referer={}",
                method, fullUrl,
                authCode == null ? "(null)" : (authCode.isEmpty() ? "(empty)" : authCode.substring(0, Math.min(8, authCode.length())) + "..."),
                state == null ? "(null)" : state,
                nvl(request.getHeader("referer")));

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
            log.warn("[DingTalk免登] 进入 /dingtalk/login 但 code 为空。method={} fullUrl={} referer={}",
                    method, fullUrl, nvl(request.getHeader("referer")));
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

    // ================== 入口 A：sso 中转页（已登录快检 / JSAPI 取 authCode → POST 到 /login） ==================

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

    // ================== 轻量限流（滑动窗口，仅内存，重启即清） ==================

    /** 单 IP 窗口内的请求时间戳队列 */
    private final java.util.Map<String, java.util.Deque<Long>> rateLimitMap = new java.util.concurrent.ConcurrentHashMap<>();

    private static final int RATE_LIMIT_MAX = 10;          // 窗口内最大请求数
    private static final long RATE_LIMIT_WINDOW_MS = 60_000L; // 窗口 60 秒

    private boolean rateLimitAllow(HttpServletRequest request)
    {
        String ip = clientIp(request);
        // 【花生壳/穿透兼容】内网穿透不转发真实 IP，所有外部用户在 Tomcat 看来都是 127.0.0.1，
        // 按 IP 限流会把全员挤进一个桶 → 穿透流量（本机回环）直接放行，限流只针对直连外部 IP。
        if ("127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "localhost".equals(ip)) return true;
        long now = System.currentTimeMillis();
        java.util.Deque<Long> q = rateLimitMap.compute(ip, (k, v) -> v != null ? v : new java.util.concurrent.ConcurrentLinkedDeque<>());
        // 清理过期时间戳 + 追加当前
        while (!q.isEmpty() && now - q.peekFirst() > RATE_LIMIT_WINDOW_MS) q.pollFirst();
        q.addLast(now);
        // 防内存膨胀：IP 数量超过 1 万时整体清空（极端扫描场景，丢精度保内存）
        if (rateLimitMap.size() > 10_000) rateLimitMap.clear();
        return q.size() <= RATE_LIMIT_MAX;
    }

    private static String clientIp(HttpServletRequest request)
    {
        String xff = request.getHeader("x-forwarded-for");
        if (StringUtils.isNotBlank(xff)) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

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
    /** 站内路径白名单：以 / 开头且非 //（防开放重定向），空值默认 /index；用于 dtok 的 go 参数 */
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
