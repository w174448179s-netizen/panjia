package com.panjia.framework.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;
import com.panjia.common.constant.Constants;

/**
 * 资源文件配置加载
 * 
 * @author panjia
 */
@Configuration
public class I18nConfig implements WebMvcConfigurer
{
    @Bean
    public LocaleResolver localeResolver()
    {
        // 【关键】子类化 SessionLocaleResolver：解析 locale 读 HttpSession 失败时回落默认语言。
        // 场景：带着上次的活会话再次免登（第二次打开钉钉应用）时，subject.login() 会替换旧 session，
        // 但请求上下文里绑定的还是旧 sessionId 的 ShiroHttpSession；DispatcherServlet.render 阶段
        // SessionLocaleResolver.resolveLocale 去读这个已删除的 session → UnknownSessionException
        // → 已登录成功的响应被覆盖成 500。回落默认语言后不再触碰失效 session，问题消除。
        SessionLocaleResolver slr = new SessionLocaleResolver()
        {
            @Override
            public java.util.Locale resolveLocale(jakarta.servlet.http.HttpServletRequest request)
            {
                try
                {
                    return super.resolveLocale(request);
                }
                catch (Exception e)
                {
                    return Constants.DEFAULT_LOCALE;
                }
            }
        };
        // 默认语言
        slr.setDefaultLocale(Constants.DEFAULT_LOCALE);
        return slr;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor()
    {
        LocaleChangeInterceptor lci = new LocaleChangeInterceptor();
        // 参数名
        lci.setParamName("lang");
        return lci;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry)
    {
        registry.addInterceptor(localeChangeInterceptor());
    }
}