package com.panjia.framework.config;

import jakarta.servlet.DispatcherType;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import com.panjia.framework.web.filter.HttpsSchemeEnforceFilter;

/**
 * 程序注解配置
 *
 * @author panjia
 */
@Configuration
// 表示通过aop框架暴露该代理对象,AopContext能够访问
@EnableAspectJAutoProxy(exposeProxy = true)
// 指定要扫描的Mapper类的包的路径
@MapperScan("com.panjia.**.mapper")
public class ApplicationConfig
{
    /**
     * 注册 HTTPS 协议强制 Filter（内网穿透兼容层）。
     * <p>
     * 只对钉钉专用路径生效：{@code /dingtalk/*}。
     * 目的：花生壳等内网穿透工具 HTTPS 隧道不转发 X-Forwarded-Proto 头，导致 Tomcat 仍认为
     * 当前是 HTTP 连接，Cookie 不带 Secure、302 Location 写成 http://，Safari/WKWebView
     * 在 HTTPS 页面直接拒收 Cookie 或 block 混合内容 → 登录白屏 NSURLErrorNetworkConnectionLost。
     * <p>
     * Order = HIGHEST_PRECEDENCE + 1：确保在 Shiro、Xss、所有 Spring Web filter 之前执行，
     * 让后续 filter / dispatch servlet 看到的 request.scheme=https 是一致的。
     */
    @Bean
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public FilterRegistrationBean httpsSchemeEnforceFilter()
    {
        FilterRegistrationBean registration = new FilterRegistrationBean();
        registration.setFilter(new HttpsSchemeEnforceFilter());
        registration.addUrlPatterns("/dingtalk/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.FORWARD, DispatcherType.INCLUDE);
        registration.setName("httpsSchemeEnforceFilter");
        // Highest + 1，比 XssFilter 再早一点（XssFilter 是 HIGHEST_PRECEDENCE）
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    /**
     * 启动时后台预热 Yauaa UA 解析器。
     * <p>
     * UserAgentUtils 里的 UserAgentAnalyzer 是静态字段，类首次加载时构建需要 ~4 秒
     * （加载 122 个 UA 规则 yaml + 构建 20 万条 matcher，见日志 YauaaVersion / Built in xxxx msec）。
     * 之前没人预热：每次重启后「第一个登录」触发类加载，登录请求被阻塞 4 秒，
     * 叠加花生壳隧道延迟后逼近/超过钉钉 WKWebView 导航超时 → NSURLErrorNetworkConnectionLost。
     * <p>
     * 这里在应用启动后用后台线程立即触发类加载 + 预解析，把这笔一次性开销从用户请求路径挪到启动阶段。
     */
    @jakarta.annotation.PostConstruct
    public void warmUpUserAgentAnalyzer()
    {
        Thread t = new Thread(() -> {
            try
            {
                long start = System.currentTimeMillis();
                // 触发 UserAgentUtils 类加载与 matcher 构建，并预填一次解析缓存
                com.panjia.common.utils.http.UserAgentUtils.getBrowser(
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Safari/605.1.15 DingTalk(8.3.15-macOS) nw DTWKWebView webDt/PC");
                org.slf4j.LoggerFactory.getLogger("warmup")
                        .info("[Warmup] Yauaa UA 解析器预热完成，耗时 {} ms", System.currentTimeMillis() - start);
            }
            catch (Throwable e)
            {
                org.slf4j.LoggerFactory.getLogger("warmup")
                        .warn("[Warmup] Yauaa UA 解析器预热失败（不影响功能，首个登录会稍慢）：{}", e.getMessage());
            }
        }, "yauaa-warmup");
        t.setDaemon(true);
        t.start();
    }
}
