package com.panjia.framework.web.filter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * HTTPS 协议强制 Filter（内网穿透兼容层）。
 * <p>
 * 【问题背景】
 * 使用花生壳/Ngrok/FRP 等 HTTPS 内网穿透工具时，典型链路为：
 * <pre>
 * 钉钉浏览器 (HTTPS) ──────▶ 花生壳服务器 ──────▶ Tomcat (HTTP 127.0.0.1:8080)
 *                           HTTPS 隧道终止             实际收到的仍是 HTTP 请求
 * </pre>
 * 大部分免费内网穿透工具不转发 {@code X-Forwarded-Proto} / {@code X-Forwarded-Host} 头，
 * 即使配置了 {@code server.forward-headers-strategy=native}，Tomcat 仍然：
 * <ul>
 *   <li>{@code request.isSecure() = false}</li>
 *   <li>{@code request.getScheme() = "http"}</li>
 *   <li>{@code request.getServerPort() = 8080}</li>
 * </ul>
 * 进而引发以下联动问题：
 * <ol>
 *   <li>Shiro {@code SimpleCookie.saveTo()} 按 {@code isSecure()} 决定是否写 {@code Secure}
 *       标志 → 不带 Secure 的 JSESSIONID 被 Safari/WKWebView 在 HTTPS 页面直接拒收 → 登录成功后
 *       仍带旧 JSESSIONID → UnknownSessionException 白屏。</li>
 *   <li>Tomcat {@code sendRedirect()} 拼完整 Location 时写成 {@code http://...} → HTTPS 页面
 *       302 跳 HTTP 触发 WKWebView 混合内容拦截 / 307 内部 upgrade → 额外 RTT + 超时风险。</li>
 *   <li>Thymeleaf {@code @{/xxx}} 等模板按 scheme 生成链接，拼出的静态资源为 HTTP 协议，
 *       HTTPS 页面加载时被混合内容策略 block，页面加载卡住触发 NSURLErrorNetworkConnectionLost。</li>
 * </ol>
 * <p>
 * 【本 Filter 做什么】
 * 对命中的 URL 路径，包装 {@link HttpServletRequest}，强制返回：
 * <ul>
 *   <li>{@code getScheme()} = {@code "https"}</li>
 *   <li>{@code isSecure()} = {@code true}</li>
 *   <li>{@code getServerName()} = 原 Host 头（若存在）或 {@code getServerName()}</li>
 *   <li>{@code getServerPort()} = 443（HTTPS 默认端口，不污染 URL 显示）</li>
 *   <li>保留 X-Forwarded-Proto / X-Forwarded-Host 头（若容器后续还要用 native strategy）</li>
 * </ul>
 * <p>
 * 【为什么不直接全站生效】
 * 本地开发环境通常用 HTTP：如果直接强制 https，Tomcat 的静态资源、Cookie 行为、登录跳转
 * 都可能出现混乱。这里默认只匹配钉钉登录路径（{@code /dingtalk/*}）。如果后续部署到云服务器，
 * 配了真正的 Nginx + 转发头，可在 FilterRegistrationBean 里扩大 urlPatterns。
 */
public class HttpsSchemeEnforceFilter implements Filter
{
    @Override
    public void init(FilterConfig filterConfig) throws ServletException
    {
        // 无初始化逻辑
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException
    {
        if (!(req instanceof HttpServletRequest))
        {
            chain.doFilter(req, resp);
            return;
        }
        HttpServletRequest httpReq = (HttpServletRequest) req;
        chain.doFilter(new HttpsEnforceWrapper(httpReq), resp);
    }

    @Override
    public void destroy()
    {
        // 无销毁逻辑
    }

    /**
     * 包装 HttpServletRequest：强制 scheme=https, secure=true, port=443, serverName=Host 头。
     */
    static class HttpsEnforceWrapper extends HttpServletRequestWrapper
    {
        private final String originalHost;

        HttpsEnforceWrapper(HttpServletRequest req)
        {
            super(req);
            String h = req.getHeader("Host");
            if (h != null && !h.isEmpty())
            {
                // Host 头可能是 host:port，如果是 :443 则去掉（更干净）
                if (h.endsWith(":443")) { h = h.substring(0, h.length() - 4); }
                else if (h.endsWith(":80"))  { h = h.substring(0, h.length() - 3); }
            }
            this.originalHost = (h != null && !h.isEmpty()) ? h : req.getServerName();
        }

        @Override public String getScheme() { return "https"; }
        @Override public boolean isSecure() { return true; }
        @Override public String getServerName() { return originalHost; }
        @Override public int getServerPort() { return 443; }

        /**
         * getRequestURL() 由 Tomcat 基于 serverName+scheme+port 拼，默认实现会调这三个 get 方法，
         * 所以只要 wrapper 覆写了上面三个，getRequestURL() 就已经返回 https://host/path 了。
         * 这里只是冗余保险。
         */
        @Override
        public StringBuffer getRequestURL()
        {
            StringBuffer sb = super.getRequestURL();
            // 兜底替换：如果默认实现没用 wrapper 的 getter（极少数容器行为），再做字符串修正
            String s = sb.toString();
            if (s.startsWith("http:"))
            {
                return new StringBuffer(128).append("https:").append(s, 5, s.length());
            }
            return sb;
        }

        /**
         * getHeader("X-Forwarded-Proto") 若已被外层填了 https 则保留原值，否则补上。
         * server.forward-headers-strategy=native（Tomcat RemoteIpValve）通常在 filter 之前就跑完了，
         * 这里补了也没副作用，只是兼容后续可能在 filter 链里读取该头的业务代码。
         */
        @Override
        public String getHeader(String name)
        {
            if ("X-Forwarded-Proto".equalsIgnoreCase(name))
            {
                String v = super.getHeader(name);
                return (v == null || v.isEmpty()) ? "https" : v;
            }
            if ("X-Forwarded-Scheme".equalsIgnoreCase(name))
            {
                String v = super.getHeader(name);
                return (v == null || v.isEmpty()) ? "https" : v;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name)
        {
            if ("X-Forwarded-Proto".equalsIgnoreCase(name) || "X-Forwarded-Scheme".equalsIgnoreCase(name))
            {
                String v = this.getHeader(name);
                return Collections.enumeration(Collections.singletonList(v));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames()
        {
            // 把 X-Forwarded-Proto / X-Forwarded-Scheme 合并到 header names 里
            Enumeration<String> parentNames = super.getHeaderNames();
            Map<String, Boolean> seen = new HashMap<>(16);
            List<String> names = new java.util.ArrayList<>(32);
            boolean hasFp = false, hasFs = false;
            while (parentNames != null && parentNames.hasMoreElements())
            {
                String n = parentNames.nextElement();
                if (seen.put(n.toLowerCase(), Boolean.TRUE) == null) names.add(n);
                if ("X-Forwarded-Proto".equalsIgnoreCase(n)) hasFp = true;
                if ("X-Forwarded-Scheme".equalsIgnoreCase(n)) hasFs = true;
            }
            if (!hasFp) names.add("X-Forwarded-Proto");
            if (!hasFs) names.add("X-Forwarded-Scheme");
            return Collections.enumeration(names);
        }
    }
}
