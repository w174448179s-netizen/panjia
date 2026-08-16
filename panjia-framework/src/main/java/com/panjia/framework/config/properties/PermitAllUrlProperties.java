package com.panjia.framework.config.properties;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.RegExUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import com.panjia.common.annotation.Anonymous;

/**
 * 设置Anonymous注解允许匿名访问的url
 * <p>
 * 扫描规则：带 {@code @Controller} 的类下，类或方法上标注 {@code @Anonymous} 的方法，
 * 把它的 URL（类 @RequestMapping + 方法上的 @RequestMapping / @GetMapping / @PostMapping /
 * @PutMapping / @DeleteMapping / @PatchMapping）加入 Shiro anon 链。
 *
 * @author panjia
 */
@Configuration
public class PermitAllUrlProperties implements InitializingBean, ApplicationContextAware
{
    private static final Logger log = LoggerFactory.getLogger(PermitAllUrlProperties.class);

    private static final Pattern PATTERN = Pattern.compile("\\{(.*?)\\}");

    private static final String ASTERISK = "*";

    private ApplicationContext applicationContext;

    private List<String> urls = new ArrayList<>();

    @Override
    public void afterPropertiesSet() throws Exception
    {
        String basePackage;
        try
        {
            basePackage = AutoConfigurationPackages.get(applicationContext.getAutowireCapableBeanFactory()).get(0);
        }
        catch (IllegalArgumentException e)
        {
            // IDE debug / 非典型启动时 AutoConfigurationPackages 可能为空，回退到 panjia 根包
            log.warn("[PermitAll] AutoConfigurationPackages 为空，回退到 com.panjia 扫描：{}", e.getMessage());
            basePackage = "com.panjia";
        }

        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        Set<String> seen = new HashSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(basePackage))
        {
            Class<?> beanClass = Class.forName(bd.getBeanClassName());
            RequestMapping base = beanClass.getAnnotation(RequestMapping.class);
            String[] baseUrl = base != null ? base.value() : new String[] {};
            boolean classAnonymous = beanClass.isAnnotationPresent(Anonymous.class);
            for (Method method : beanClass.getDeclaredMethods())
            {
                boolean methodAnonymous = method.isAnnotationPresent(Anonymous.class);
                if (!classAnonymous && !methodAnonymous)
                {
                    continue;
                }
                // 兼容 @RequestMapping + 常见短注解 @GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping
                List<String[]> allUriGroups = new ArrayList<>();
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping != null && mapping.value().length > 0) allUriGroups.add(mapping.value());
                GetMapping gm = method.getAnnotation(GetMapping.class);
                if (gm != null && gm.value().length > 0) allUriGroups.add(gm.value());
                PostMapping pm = method.getAnnotation(PostMapping.class);
                if (pm != null && pm.value().length > 0) allUriGroups.add(pm.value());
                PutMapping pum = method.getAnnotation(PutMapping.class);
                if (pum != null && pum.value().length > 0) allUriGroups.add(pum.value());
                DeleteMapping dm = method.getAnnotation(DeleteMapping.class);
                if (dm != null && dm.value().length > 0) allUriGroups.add(dm.value());
                PatchMapping pam = method.getAnnotation(PatchMapping.class);
                if (pam != null && pam.value().length > 0) allUriGroups.add(pam.value());
                for (String[] uris : allUriGroups)
                {
                    for (String u : rebuildUrl(baseUrl, uris))
                    {
                        if (seen.add(u))
                        {
                            urls.add(u);
                        }
                    }
                }
            }
        }
        log.info("[PermitAll] 扫描完成，共 {} 个匿名URL：{}", urls.size(),
                urls.size() <= 50 ? urls : urls.subList(0, 50) + "...(截断，共" + urls.size() + ")");
    }

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException
    {
        this.applicationContext = context;
    }

    @SuppressWarnings("deprecation")
    private List<String> rebuildUrl(String[] bases, String[] uris)
    {
        List<String> urls = new ArrayList<>();
        for (String base : bases)
        {
            for (String uri : uris)
            {
                urls.add(prefix(base) + prefix(RegExUtils.replaceAll(uri, PATTERN, ASTERISK)));
            }
        }
        return urls;
    }

    private String prefix(String seg)
    {
        return seg.startsWith("/") ? seg : "/" + seg;
    }

    public List<String> getUrls()
    {
        return urls;
    }

    public void setUrls(List<String> urls)
    {
        this.urls = urls;
    }
}
