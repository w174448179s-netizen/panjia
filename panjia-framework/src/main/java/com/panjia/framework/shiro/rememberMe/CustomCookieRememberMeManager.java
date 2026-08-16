package com.panjia.framework.shiro.rememberMe;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.SubjectContext;
import org.apache.shiro.web.mgt.CookieRememberMeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.panjia.common.core.domain.entity.SysRole;
import com.panjia.common.core.domain.entity.SysUser;
import com.panjia.common.utils.spring.SpringUtils;
import com.panjia.framework.shiro.service.SysLoginService;

/**
 * 自定义CookieRememberMeManager
 * <p>
 * 相比父类做了两件事：
 * <ol>
 *   <li>rememberIdentity：序列化时先把 SysRole.permissions 置空，避免 cookie 膨胀触发 HTTP 头过大；写完再恢复。</li>
 *   <li>getRememberedPrincipals：解密失败（密钥变化/cookie损坏）时安静返回 null，而不是抛 CryptoException 刷屏；
 *       解密成功后再调用 SysLoginService.setRolePermission 把权限字符串补回到角色上。</li>
 * </ol>
 *
 * @author panjia
 */
public class CustomCookieRememberMeManager extends CookieRememberMeManager
{
    private static final Logger log = LoggerFactory.getLogger(CustomCookieRememberMeManager.class);

    /**
     * 记住我时去掉角色的permissions权限字符串，防止http请求头过大。
     */
    @Override
    protected void rememberIdentity(Subject subject, PrincipalCollection principalCollection)
    {
        Map<SysRole, Set<String>> rolePermissions = new HashMap<>();
        // 清除角色的permissions权限字符串
        for (Object principal : principalCollection)
        {
            if (principal instanceof SysUser)
            {
                List<SysRole> roles = ((SysUser) principal).getRoles();
                for (SysRole role : roles)
                {
                    rolePermissions.put(role, role.getPermissions());
                    role.setPermissions(null);
                }
            }
        }
        byte[] bytes = convertPrincipalsToBytes(principalCollection);
        // 恢复角色的permissions权限字符串
        for (Object principal : principalCollection)
        {
            if (principal instanceof SysUser)
            {
                List<SysRole> roles = ((SysUser) principal).getRoles();
                for (SysRole role : roles)
                {
                    role.setPermissions(rolePermissions.get(role));
                }
            }
        }
        rememberSerializedIdentity(subject, bytes);
    }

    /**
     * 取记住我身份。
     * <p>
     * 父类默认在 rememberMe cookie 解密失败时会抛 {@code CryptoException}，
     * 进而导致 {@code DefaultSecurityManager.getRememberedIdentity()} 打一堆 WARN，
     * 更关键的是会把当前请求的 subject 链路「forget remembered identity」，
     * 影响钉钉免登这种「无本地 session、靠 code 直接新登录」的场景。
     * 这里兜底 catch 所有异常，静默返回 null，当作无记住我身份，让后续流程走正常登录。
     */
    @Override
    public PrincipalCollection getRememberedPrincipals(SubjectContext subjectContext)
    {
        PrincipalCollection principals;
        try
        {
            principals = super.getRememberedPrincipals(subjectContext);
        }
        catch (Exception e)
        {
            // 典型：rememberMe cookie 过期/密钥变更/损坏 → 就当没有记住我，用户重新登录即可
            log.debug("[RememberMe] 解析rememberMe cookie失败（通常是密钥变更或旧cookie损坏），将按未登录处理：{}",
                    e.getMessage());
            return null;
        }
        if (principals == null || principals.isEmpty())
        {
            return principals;
        }
        for (Object principal : principals)
        {
            if (principal instanceof SysUser)
            {
                SpringUtils.getBean(SysLoginService.class).setRolePermission((SysUser) principal);
            }
        }
        return principals;
    }
}
