package com.panjia.framework.shiro.web.filter;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.apache.shiro.session.SessionException;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.panjia.common.constant.Constants;
import com.panjia.common.core.domain.entity.SysUser;
import com.panjia.common.utils.MessageUtils;
import com.panjia.common.utils.ShiroUtils;
import com.panjia.common.utils.StringUtils;
import com.panjia.common.utils.spring.SpringUtils;
import com.panjia.framework.manager.AsyncManager;
import com.panjia.framework.manager.factory.AsyncFactory;
import com.panjia.system.service.ISysUserOnlineService;

/**
 * 退出过滤器
 * 
 * @author panjia
 */
public class LogoutFilter extends org.apache.shiro.web.filter.authc.LogoutFilter
{
    private static final Logger log = LoggerFactory.getLogger(LogoutFilter.class);

    /**
     * 退出后重定向的地址
     */
    private String loginUrl;

    public String getLoginUrl()
    {
        return loginUrl;
    }

    public void setLoginUrl(String loginUrl)
    {
        this.loginUrl = loginUrl;
    }

    @Override
    protected boolean preHandle(ServletRequest request, ServletResponse response) throws Exception
    {
        try
        {
            Subject subject = getSubject(request, response);
            String redirectUrl = getRedirectUrl(request, response, subject);
            try
            {
                SysUser user = ShiroUtils.getSysUser();
                if (StringUtils.isNotNull(user))
                {
                    String loginName = user.getLoginName();
                    // 记录用户退出日志
                    AsyncManager.me().execute(AsyncFactory.recordLogininfor(loginName, Constants.LOGOUT, MessageUtils.message("user.logout.success")));
                    // 清理缓存
                    SpringUtils.getBean(ISysUserOnlineService.class).removeUserCache(loginName, ShiroUtils.getSessionId());
                }
                // 退出登录
                subject.logout();
            }
            catch (SessionException ise)
            {
                log.error("logout fail.", ise);
            }
            // 【钉钉 WebView 特判】不发 302，改用 forward 直接渲染 /dingtalk/sso。
            // 实测钉钉 PC WKWebView 对「导航 GET → 302」跟随不稳定：302 已正常发出，
            // 但 WebView 不发后续请求，直接报 NSURLErrorNetworkConnectionLost
            // （登录路径已用「直渲染 dtok」绕开，此处同方处理）。
            // forward 在同一请求内完成、无网络跳转；session 已 logout，sso 页自动重走免登。
            if (isDingTalkRequest(request))
            {
                try
                {
                    request.getRequestDispatcher("/dingtalk/sso").forward(request, response);
                    return false;
                }
                catch (Exception e)
                {
                    log.warn("[LogoutFilter] forward 到 /dingtalk/sso 失败，回退 302：{}", e.getMessage());
                }
            }
            issueRedirect(request, response, redirectUrl);
        }
        catch (Exception e)
        {
            log.error("Encountered session exception during logout.  This can generally safely be ignored.", e);
        }
        return false;
    }

    /** User-Agent 是否来自钉钉（PC DTWKWebView / 移动端 AliApp(DingTalk)） */
    private boolean isDingTalkRequest(ServletRequest request)
    {
        try
        {
            if (request instanceof jakarta.servlet.http.HttpServletRequest httpRequest)
            {
                String ua = httpRequest.getHeader("User-Agent");
                return StringUtils.isNotEmpty(ua) && ua.contains("DingTalk");
            }
        }
        catch (Exception ignore) { }
        return false;
    }

    /**
     * 退出跳转URL。
     * <p>
     * 【钉钉 WebView 特判】User-Agent 含 DingTalk 时退出后跳 /dingtalk/sso 而非 /login
     * （钉钉用户没有密码概念；/login 全套资源在 1Mbps 隧道下加载多秒）。
     * 注意：钉钉请求实际走 preHandle 里的 forward 直渲染，本方法的结果只作为 forward 失败的回退。
     */
    @Override
    protected String getRedirectUrl(ServletRequest request, ServletResponse response, Subject subject)
    {
        try
        {
            if (request instanceof jakarta.servlet.http.HttpServletRequest httpRequest)
            {
                String ua = httpRequest.getHeader("User-Agent");
                if (StringUtils.isNotEmpty(ua) && ua.contains("DingTalk"))
                {
                    return "/dingtalk/sso";
                }
            }
        }
        catch (Exception ignore) { /* 判断失败则走默认 /login */ }
        String url = getLoginUrl();
        if (StringUtils.isNotEmpty(url))
        {
            return url;
        }
        return super.getRedirectUrl(request, response, subject);
    }
}
