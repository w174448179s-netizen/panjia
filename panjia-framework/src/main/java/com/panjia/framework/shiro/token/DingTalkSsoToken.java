package com.panjia.framework.shiro.token;

import org.apache.shiro.authc.AuthenticationToken;

/**
 * 钉钉免登专用 Token。
 * <p>与 {@link org.apache.shiro.authc.UsernamePasswordToken} 并列：
 * <ul>
 *   <li>UsernamePasswordToken — 账号密码登录 → UserRealm 调 loginService.login(user,pass)</li>
 *   <li>DingTalkSsoToken — 钉钉 SSO 免密 → UserRealm 调 loginService.ssoLogin(loginName)</li>
 * </ul>
 */
public class DingTalkSsoToken implements AuthenticationToken
{
    private static final long serialVersionUID = 1L;

    private final String loginName;

    public DingTalkSsoToken(String loginName)
    {
        this.loginName = loginName;
    }

    @Override public Object getPrincipal() { return loginName; }
    @Override public Object getCredentials() { return "dingtalk_sso_no_password"; }

    public String getLoginName() { return loginName; }
}
