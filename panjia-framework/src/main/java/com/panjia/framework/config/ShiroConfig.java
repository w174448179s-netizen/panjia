package com.panjia.framework.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.io.IOUtils;
import org.apache.shiro.cache.ehcache.EhCacheManager;
import org.apache.shiro.config.ConfigurationException;
import org.apache.shiro.lang.codec.Base64;
import org.apache.shiro.lang.io.ResourceUtils;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.servlet.SimpleCookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.panjia.common.constant.Constants;
import com.panjia.common.utils.StringUtils;
import com.panjia.common.utils.security.CipherUtils;
import com.panjia.common.utils.spring.SpringUtils;
import com.panjia.framework.config.properties.PermitAllUrlProperties;
import com.panjia.framework.shiro.realm.UserRealm;
import com.panjia.framework.shiro.rememberMe.CustomCookieRememberMeManager;
import com.panjia.framework.shiro.session.OnlineSessionDAO;
import com.panjia.framework.shiro.session.OnlineSessionFactory;
import com.panjia.framework.shiro.web.CustomShiroFilterFactoryBean;
import com.panjia.framework.shiro.web.filter.LogoutFilter;
import com.panjia.framework.shiro.web.filter.captcha.CaptchaValidateFilter;
import com.panjia.framework.shiro.web.filter.csrf.CsrfValidateFilter;
import com.panjia.framework.shiro.web.filter.kickout.KickoutSessionFilter;
import com.panjia.framework.shiro.web.filter.online.OnlineSessionFilter;
import com.panjia.framework.shiro.web.filter.sync.SyncOnlineSessionFilter;
import com.panjia.framework.shiro.web.session.OnlineWebSessionManager;
import com.panjia.framework.shiro.web.session.SpringSessionValidationScheduler;
import at.pollux.thymeleaf.shiro.dialect.ShiroDialect;
import jakarta.servlet.Filter;

/**
 * 权限配置加载
 * 
 * @author panjia
 */
@Configuration
public class ShiroConfig
{
    private static final Logger log = LoggerFactory.getLogger(ShiroConfig.class);

    /**
     * Session超时时间，单位为毫秒（默认30分钟）
     */
    @Value("${shiro.session.expireTime}")
    private int expireTime;

    /**
     * 相隔多久检查一次session的有效性，单位毫秒，默认就是10分钟
     */
    @Value("${shiro.session.validationInterval}")
    private int validationInterval;

    /**
     * 同一个用户最大会话数
     */
    @Value("${shiro.session.maxSession}")
    private int maxSession;

    /**
     * 踢出之前登录的/之后登录的用户，默认踢出之前登录的用户
     */
    @Value("${shiro.session.kickoutAfter}")
    private boolean kickoutAfter;

    /**
     * 验证码开关
     */
    @Value("${shiro.user.captchaEnabled}")
    private boolean captchaEnabled;

    /**
     * 验证码类型
     */
    @Value("${shiro.user.captchaType}")
    private String captchaType;

    /**
     * 设置Cookie的域名
     */
    @Value("${shiro.cookie.domain}")
    private String domain;

    /**
     * 设置cookie的有效访问路径
     */
    @Value("${shiro.cookie.path}")
    private String path;

    /**
     * 设置HttpOnly属性
     */
    @Value("${shiro.cookie.httpOnly}")
    private boolean httpOnly;

    /**
     * 设置Cookie的过期时间，秒为单位
     */
    @Value("${shiro.cookie.maxAge}")
    private int maxAge;

    /**
     * 设置cipherKey密钥
     */
    @Value("${shiro.cookie.cipherKey}")
    private String cipherKey;

    /**
     * 登录地址
     */
    @Value("${shiro.user.loginUrl}")
    private String loginUrl;

    /**
     * 权限认证失败地址
     */
    @Value("${shiro.user.unauthorizedUrl}")
    private String unauthorizedUrl;

    /**
     * 是否开启记住我功能
     */
    @Value("${shiro.rememberMe.enabled: false}")
    private boolean rememberMe;

    /**
     * 是否开启csrf
     */
    @Value("${csrf.enabled: false}")
    private boolean csrfEnabled;

    /**
     * csrf白名单链接
     */
    @Value("${csrf.whites: ''}")
    private String csrfWhites;

    @Autowired
    private PermitAllUrlProperties permitAllUrl;

    /**
     * 缓存管理器 使用Ehcache实现
     */
    @Bean
    public EhCacheManager getEhCacheManager()
    {
        net.sf.ehcache.CacheManager cacheManager = net.sf.ehcache.CacheManager.getCacheManager("panjia");
        EhCacheManager em = new EhCacheManager();
        if (StringUtils.isNull(cacheManager))
        {
            em.setCacheManager(new net.sf.ehcache.CacheManager(getCacheManagerConfigFileInputStream()));
            return em;
        }
        else
        {
            em.setCacheManager(cacheManager);
            return em;
        }
    }

    /**
     * 返回配置文件流 避免ehcache配置文件一直被占用，无法完全销毁项目重新部署
     */
    protected InputStream getCacheManagerConfigFileInputStream()
    {
        String configFile = "classpath:ehcache/ehcache-shiro.xml";
        InputStream inputStream = null;
        try
        {
            inputStream = ResourceUtils.getInputStreamForPath(configFile);
            byte[] b = IOUtils.toByteArray(inputStream);
            InputStream in = new ByteArrayInputStream(b);
            return in;
        }
        catch (IOException e)
        {
            throw new ConfigurationException(
                    "Unable to obtain input stream for cacheManagerConfigFile [" + configFile + "]", e);
        }
        finally
        {
            IOUtils.closeQuietly(inputStream);
        }
    }

    /**
     * 自定义Realm
     */
    @Bean
    public UserRealm userRealm(EhCacheManager cacheManager)
    {
        UserRealm userRealm = new UserRealm();
        userRealm.setAuthorizationCacheName(Constants.SYS_AUTH_CACHE);
        userRealm.setCacheManager(cacheManager);
        return userRealm;
    }

    /**
     * 自定义sessionDAO会话
     */
    @Bean
    public OnlineSessionDAO sessionDAO()
    {
        OnlineSessionDAO sessionDAO = new OnlineSessionDAO();
        return sessionDAO;
    }

    /**
     * 自定义sessionFactory会话
     */
    @Bean
    public OnlineSessionFactory sessionFactory()
    {
        OnlineSessionFactory sessionFactory = new OnlineSessionFactory();
        return sessionFactory;
    }

    /**
     * 会话管理器
     */
    @Bean
    public OnlineWebSessionManager sessionManager()
    {
        OnlineWebSessionManager manager = new OnlineWebSessionManager();
        // 加入缓存管理器
        manager.setCacheManager(getEhCacheManager());
        // 删除过期的session
        manager.setDeleteInvalidSessions(true);
        // 设置全局session超时时间
        manager.setGlobalSessionTimeout(expireTime * 60 * 1000);
        // 去掉 JSESSIONID
        manager.setSessionIdUrlRewritingEnabled(false);
        // 定义要使用的无效的Session定时调度器
        manager.setSessionValidationScheduler(SpringUtils.getBean(SpringSessionValidationScheduler.class));
        // 是否定时检查session
        manager.setSessionValidationSchedulerEnabled(true);
        // 自定义SessionDao
        manager.setSessionDAO(sessionDAO());
        // 自定义sessionFactory
        manager.setSessionFactory(sessionFactory());
        // ===== 关键修复：显式配置 sessionIdCookie（JSESSIONID），适配钉钉 WKWebView + 反向代理场景 =====
        // Shiro DefaultWebSessionManager 会懒加载一个默认 SimpleCookie，但默认 SameSite 未设置，
        // 在钉钉内嵌 WKWebView（Safari 内核）中 Cookie 可能被静默丢弃，导致登录成功后 302→/index
        // 仍然携带旧 JSESSIONID，触发 UnknownSessionException，页面白屏无响应。
        // 这里主动创建一个带 SameSite=Lax、httpOnly=true、path=/ 的 cookie 模板交给 SessionManager。
        // ------------------------------------------------------------------------------------------
        // 【HTTPS 强制】Secure=true 直接硬编码，不再依赖 request.isSecure() 的动态判断。
        //   原因：花生壳/Ngrok 等内网穿透工具通常只做 HTTPS 隧道，不转发 X-Forwarded-Proto 头，
        //         Tomcat 实际收到的还是 HTTP（request.isSecure()=false），SimpleCookie.saveTo
        //         就会写 Set-Cookie 时不带 Secure。Safari/WKWebView 在 HTTPS 页面看到不带 Secure
        //         的 Cookie 会直接拒收，登录成功后 sessionId 还是写不进去，白屏。
        //   钉钉开放平台硬性要求「应用首页地址」必须是 HTTPS，所以所有从钉钉工作台进来的请求
        //   一定是 HTTPS，硬编码 Secure=true 是安全的；本地开发环境若用 HTTP 访问，浏览器只是
        //   "忽略 Cookie 的 Secure 限制"（HTTP 场景会照常发送），不影响本地联调。
        // ------------------------------------------------------------------------------------------
        SimpleCookie sessionIdCookie = new SimpleCookie("JSESSIONID");
        sessionIdCookie.setHttpOnly(httpOnly);
        sessionIdCookie.setPath(path);
        sessionIdCookie.setSecure(true); // 钉钉H5强制 HTTPS → Cookie 必须带 Secure，Safari 严格校验
        // domain 默认空（使用当前访问域名）。显式填错反而会让浏览器拒绝写入 Cookie，
        // 因此这里只在配置非空时才设置，保持与 rememberMeCookie 一致的策略。
        if (StringUtils.isNotEmpty(domain))
        {
            sessionIdCookie.setDomain(domain);
        }
        // -1 表示「会话级 Cookie」（浏览器关闭即失效），与 RuoYi 原有行为一致；不要填 0（立即删除）
        sessionIdCookie.setMaxAge(-1);
        // SameSite=Lax：允许顶级导航（302 跳转）携带 Cookie，同时防普通 CSRF。
        // Shiro 2.2.0 的 SimpleCookie 原生支持 setSameSite（Cookie.SameSiteOptions），
        // 由 SessionManager 统一写出 Set-Cookie，保证【单一来源】——
        // 之前 DingTalkLoginController 手动 addHeader 写的第二份 JSESSIONID（后写覆盖）
        // 会导致浏览器丢弃正确 SameSite 属性的那份，引发登录后 1~2 分钟 UnknownSessionException。
        sessionIdCookie.setSameSite(org.apache.shiro.web.servlet.Cookie.SameSiteOptions.LAX);
        manager.setSessionIdCookie(sessionIdCookie);
        log.info("[Shiro] 已显式配置 sessionIdCookie(JSESSIONID): httpOnly={}, path={}, secure=TRUE(钉钉HTTPS强制), domain={}, maxAge=-1(Session级), SameSite=Lax(单一来源写入)",
                httpOnly, path, StringUtils.isEmpty(domain) ? "(当前访问域名)" : domain);
        return manager;
    }

    /**
     * 安全管理器
     */
    @Bean
    public SecurityManager securityManager(UserRealm userRealm)
    {
        DefaultWebSecurityManager securityManager = new DefaultWebSecurityManager();
        // 设置realm.
        securityManager.setRealm(userRealm);
        // 记住我
        securityManager.setRememberMeManager(rememberMe ? rememberMeManager() : null);
        // 注入缓存管理器;
        securityManager.setCacheManager(getEhCacheManager());
        // session管理器
        securityManager.setSessionManager(sessionManager());
        return securityManager;
    }

    /**
     * 退出过滤器
     */
    public LogoutFilter logoutFilter()
    {
        LogoutFilter logoutFilter = new LogoutFilter();
        logoutFilter.setLoginUrl(loginUrl);
        return logoutFilter;
    }

    /**
     * csrf过滤器
     */
    public CsrfValidateFilter csrfValidateFilter()
    {
        CsrfValidateFilter csrfValidateFilter = new CsrfValidateFilter();
        csrfValidateFilter.setEnabled(csrfEnabled);
        csrfValidateFilter.setCsrfWhites(StringUtils.str2List(csrfWhites, ","));
        return csrfValidateFilter;
    }

    /**
     * Shiro过滤器配置
     */
    @Bean
    public ShiroFilterFactoryBean shiroFilterFactoryBean(SecurityManager securityManager)
    {
        CustomShiroFilterFactoryBean shiroFilterFactoryBean = new CustomShiroFilterFactoryBean();
        // Shiro的核心安全接口,这个属性是必须的
        shiroFilterFactoryBean.setSecurityManager(securityManager);
        // 身份认证失败，则跳转到登录页面的配置
        shiroFilterFactoryBean.setLoginUrl(loginUrl);
        // 权限认证失败，则跳转到指定页面
        shiroFilterFactoryBean.setUnauthorizedUrl(unauthorizedUrl);
        // Shiro连接约束配置，即过滤链的定义
        LinkedHashMap<String, String> filterChainDefinitionMap = new LinkedHashMap<>();
        // 对静态资源设置匿名访问
        filterChainDefinitionMap.put("/favicon.ico**", "anon");
        filterChainDefinitionMap.put("/panjia.png**", "anon");
        filterChainDefinitionMap.put("/html/**", "anon");
        filterChainDefinitionMap.put("/css/**", "anon");
        filterChainDefinitionMap.put("/docs/**", "anon");
        filterChainDefinitionMap.put("/fonts/**", "anon");
        filterChainDefinitionMap.put("/img/**", "anon");
        filterChainDefinitionMap.put("/ajax/**", "anon");
        filterChainDefinitionMap.put("/js/**", "anon");
        filterChainDefinitionMap.put("/panjia/**", "anon");
        filterChainDefinitionMap.put("/captcha/captchaImage**", "anon");
        // 匿名访问不鉴权注解列表（PermitAllUrlProperties 扫描 @Anonymous 得到）
        List<String> scannedAnonUrls = permitAllUrl.getUrls() == null ? new ArrayList<>() : new ArrayList<>(permitAllUrl.getUrls());
        scannedAnonUrls.forEach(url -> filterChainDefinitionMap.put(url, "anon"));
        // 【兜底】钉钉免登相关入口：即使 @Anonymous 扫描异常，也要保证这几个URL能匿名通过
        for (String u : new String[] { "/dingtalk/login", "/dingtalk/sso", "/dingtalk/logout", "/dingtalk/config", "/dingtalk/log" })
        {
            filterChainDefinitionMap.putIfAbsent(u, "anon");
        }
        // 退出 logout地址，shiro去清除session
        filterChainDefinitionMap.put("/logout", "logout");
        // 不需要拦截的访问
        filterChainDefinitionMap.put("/login", "anon,captchaValidate");
        // 系统权限列表
        // filterChainDefinitionMap.putAll(SpringUtils.getBean(IMenuService.class).selectPermsAll());

        Map<String, Filter> filters = new LinkedHashMap<String, Filter>();
        filters.put("onlineSession", onlineSessionFilter());
        filters.put("syncOnlineSession", syncOnlineSessionFilter());
        filters.put("captchaValidate", captchaValidateFilter());
        filters.put("csrfValidateFilter", csrfValidateFilter());
        filters.put("kickout", kickoutSessionFilter());
        // 注销成功，则跳转到指定页面
        filters.put("logout", logoutFilter());
        shiroFilterFactoryBean.setFilters(filters);

        // 所有请求需要认证
        filterChainDefinitionMap.put("/**", "user,kickout,onlineSession,syncOnlineSession,csrfValidateFilter");
        shiroFilterFactoryBean.setFilterChainDefinitionMap(filterChainDefinitionMap);

        // 匿名链诊断日志：启动时把所有 anon URL 打出来，便于核对 @Anonymous 是否生效
        List<String> anonList = new ArrayList<>();
        filterChainDefinitionMap.forEach((k, v) -> { if (v.startsWith("anon")) anonList.add(k); });
        log.info("[Shiro] anon 链共 {} 条：{}", anonList.size(),
                anonList.size() <= 60 ? anonList : anonList.subList(0, 60) + "...(共" + anonList.size() + "条)");
        if (!scannedAnonUrls.isEmpty())
        {
            log.info("[Shiro] @Anonymous 扫描到的URL子集：{}", scannedAnonUrls);
        }
        // 关键校验：钉钉免登入口必须在 anon 链里，否则会被 /** → user 拦截，直接 302 回 /login 丢掉 code 参数
        String[] mustAnon = { "/dingtalk/login", "/dingtalk/sso", "/dingtalk/config", "/dingtalk/logout", "/dingtalk/log" };
        for (String u : mustAnon)
        {
            if (!filterChainDefinitionMap.containsKey(u) || !filterChainDefinitionMap.get(u).startsWith("anon"))
            {
                log.error("[Shiro] 关键匿名URL {} 未进入anon链！钉钉免登会被拦截。请检查 @Anonymous 注解与 PermitAllUrlProperties 扫描。", u);
            }
        }

        return shiroFilterFactoryBean;
    }

    /**
     * 自定义在线用户处理过滤器
     */
    public OnlineSessionFilter onlineSessionFilter()
    {
        OnlineSessionFilter onlineSessionFilter = new OnlineSessionFilter();
        onlineSessionFilter.setLoginUrl(loginUrl);
        onlineSessionFilter.setOnlineSessionDAO(sessionDAO());
        return onlineSessionFilter;
    }

    /**
     * 自定义在线用户同步过滤器
     */
    public SyncOnlineSessionFilter syncOnlineSessionFilter()
    {
        SyncOnlineSessionFilter syncOnlineSessionFilter = new SyncOnlineSessionFilter();
        syncOnlineSessionFilter.setOnlineSessionDAO(sessionDAO());
        return syncOnlineSessionFilter;
    }

    /**
     * 自定义验证码过滤器
     */
    public CaptchaValidateFilter captchaValidateFilter()
    {
        CaptchaValidateFilter captchaValidateFilter = new CaptchaValidateFilter();
        captchaValidateFilter.setCaptchaEnabled(captchaEnabled);
        captchaValidateFilter.setCaptchaType(captchaType);
        return captchaValidateFilter;
    }

    /**
     * cookie 属性设置
     */
    public SimpleCookie rememberMeCookie()
    {
        SimpleCookie cookie = new SimpleCookie("rememberMe");
        cookie.setDomain(domain);
        cookie.setPath(path);
        cookie.setHttpOnly(httpOnly);
        cookie.setMaxAge(maxAge * 24 * 60 * 60);
        return cookie;
    }

    /**
     * 记住我
     */
    public CustomCookieRememberMeManager rememberMeManager()
    {
        CustomCookieRememberMeManager cookieRememberMeManager = new CustomCookieRememberMeManager();
        cookieRememberMeManager.setCookie(rememberMeCookie());
        if (StringUtils.isNotEmpty(cipherKey))
        {
            cookieRememberMeManager.setCipherKey(Base64.decode(cipherKey));
        }
        else
        {
            cookieRememberMeManager.setCipherKey(CipherUtils.generateNewKey(128, "AES").getEncoded());
        }
        return cookieRememberMeManager;
    }

    /**
     * 同一个用户多设备登录限制
     */
    public KickoutSessionFilter kickoutSessionFilter()
    {
        KickoutSessionFilter kickoutSessionFilter = new KickoutSessionFilter();
        kickoutSessionFilter.setCacheManager(getEhCacheManager());
        kickoutSessionFilter.setSessionManager(sessionManager());
        // 同一个用户最大的会话数，默认-1无限制；比如2的意思是同一个用户允许最多同时两个人登录
        kickoutSessionFilter.setMaxSession(maxSession);
        // 是否踢出后来登录的，默认是false；即后者登录的用户踢出前者登录的用户；踢出顺序
        kickoutSessionFilter.setKickoutAfter(kickoutAfter);
        // 被踢出后重定向到的地址；
        kickoutSessionFilter.setKickoutUrl("/login?kickout=1");
        return kickoutSessionFilter;
    }

    /**
     * thymeleaf模板引擎和shiro框架的整合
     */
    @Bean
    public ShiroDialect shiroDialect()
    {
        return new ShiroDialect();
    }

    /**
     * 开启Shiro注解通知器
     */
    @Bean
    public AuthorizationAttributeSourceAdvisor authorizationAttributeSourceAdvisor(
            @Qualifier("securityManager") SecurityManager securityManager)
    {
        AuthorizationAttributeSourceAdvisor authorizationAttributeSourceAdvisor = new AuthorizationAttributeSourceAdvisor();
        authorizationAttributeSourceAdvisor.setSecurityManager(securityManager);
        return authorizationAttributeSourceAdvisor;
    }
}
