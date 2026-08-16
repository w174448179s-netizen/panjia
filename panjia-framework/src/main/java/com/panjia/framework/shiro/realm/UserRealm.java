package com.panjia.framework.shiro.realm;

import java.util.HashSet;
import java.util.Set;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.ExcessiveAttemptsException;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.LockedAccountException;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.AuthorizationInfo;

import com.panjia.framework.shiro.token.DingTalkSsoToken;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.cache.Cache;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import com.panjia.common.core.domain.entity.SysUser;
import com.panjia.common.exception.user.CaptchaException;
import com.panjia.common.exception.user.RoleBlockedException;
import com.panjia.common.exception.user.UserBlockedException;
import com.panjia.common.exception.user.UserNotExistsException;
import com.panjia.common.exception.user.UserPasswordNotMatchException;
import com.panjia.common.exception.user.UserPasswordRetryLimitExceedException;
import com.panjia.common.utils.ShiroUtils;
import com.panjia.framework.shiro.service.SysLoginService;
import com.panjia.system.service.ISysMenuService;
import com.panjia.system.service.ISysRoleService;

/**
 * 自定义Realm 处理登录 权限
 * 
 * @author panjia
 */
public class UserRealm extends AuthorizingRealm
{
    private static final Logger log = LoggerFactory.getLogger(UserRealm.class);

    @Autowired
    private ISysMenuService menuService;

    @Autowired
    private ISysRoleService roleService;

    @Autowired
    private SysLoginService loginService;

    /**
     * 声明此 Realm 能处理的 Token 类型。
     * <p>注意：Shiro 默认按 {@code setAuthenticationTokenClass()} 匹配（通常只认 UsernamePasswordToken），
     * 若不加此 override，DingTalkSsoToken 会被 supports() 直接判 false 拒绝，
     * 报出 "Realm ... does not support authentication token [DingTalkSsoToken]"，
     * 即便 doGetAuthenticationInfo() 内部已经写了 instanceof 判断也进不去。
     */
    @Override
    public boolean supports(AuthenticationToken token)
    {
        return token != null
                && (token instanceof UsernamePasswordToken || token instanceof DingTalkSsoToken);
    }

    /**
     * 授权
     */
    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection arg0)
    {
        SysUser user = ShiroUtils.getSysUser();
        // 角色列表
        Set<String> roles = new HashSet<String>();
        // 功能列表
        Set<String> menus = new HashSet<String>();
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        // 管理员拥有所有权限
        if (user.isAdmin())
        {
            info.addRole("admin");
            info.addStringPermission("*:*:*");
        }
        else
        {
            roles = roleService.selectRoleKeys(user.getUserId());
            menus = menuService.selectPermsByUserId(user.getUserId());
            // 角色加入AuthorizationInfo认证对象
            info.setRoles(roles);
            // 权限加入AuthorizationInfo认证对象
            info.setStringPermissions(menus);
        }
        return info;
    }

    /**
     * 登录认证
     */
    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException
    {
        SysUser user = null;

        if (token instanceof DingTalkSsoToken)
        {
            // —— 钉钉 SSO 免密登录：身份已通过钉钉授权码校验，此处只做账号状态校验 ——
            DingTalkSsoToken sso = (DingTalkSsoToken) token;
            try
            {
                user = loginService.ssoLogin(sso.getLoginName(), "钉钉");
            }
            catch (UserNotExistsException e)
            {
                throw new UnknownAccountException(e.getMessage(), e);
            }
            catch (UserBlockedException | RoleBlockedException e)
            {
                throw new LockedAccountException(e.getMessage(), e);
            }
            catch (Exception e)
            {
                log.info("钉钉SSO对用户[{}]登录验证未通过：{}", sso.getLoginName(), e.getMessage());
                throw new AuthenticationException(e.getMessage(), e);
            }
            SimpleAuthenticationInfo info = new SimpleAuthenticationInfo(user, sso.getCredentials(), getName());
            return info;
        }

        // —— 原有账号密码登录 ——
        UsernamePasswordToken upToken = (UsernamePasswordToken) token;
        String username = upToken.getUsername();
        String password = "";
        if (upToken.getPassword() != null)
        {
            password = new String(upToken.getPassword());
        }
        try
        {
            user = loginService.login(username, password);
        }
        catch (CaptchaException e)
        {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch (UserNotExistsException e)
        {
            throw new UnknownAccountException(e.getMessage(), e);
        }
        catch (UserPasswordNotMatchException e)
        {
            throw new IncorrectCredentialsException(e.getMessage(), e);
        }
        catch (UserPasswordRetryLimitExceedException e)
        {
            throw new ExcessiveAttemptsException(e.getMessage(), e);
        }
        catch (UserBlockedException e)
        {
            throw new LockedAccountException(e.getMessage(), e);
        }
        catch (RoleBlockedException e)
        {
            throw new LockedAccountException(e.getMessage(), e);
        }
        catch (Exception e)
        {
            log.info("对用户[" + username + "]进行登录验证..验证未通过{}", e.getMessage());
            throw new AuthenticationException(e.getMessage(), e);
        }
        SimpleAuthenticationInfo info = new SimpleAuthenticationInfo(user, password, getName());
        return info;
    }

    /**
     * 清理指定用户授权信息缓存
     */
    public void clearCachedAuthorizationInfo(Object principal)
    {
        SimplePrincipalCollection principals = new SimplePrincipalCollection(principal, getName());
        this.clearCachedAuthorizationInfo(principals);
    }

    /**
     * 清理所有用户授权信息缓存
     */
    public void clearAllCachedAuthorizationInfo()
    {
        Cache<Object, AuthorizationInfo> cache = getAuthorizationCache();
        if (cache != null)
        {
            for (Object key : cache.keys())
            {
                cache.remove(key);
            }
        }
    }
}
