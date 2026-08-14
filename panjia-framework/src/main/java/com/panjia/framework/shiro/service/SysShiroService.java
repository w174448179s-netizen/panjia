package com.panjia.framework.shiro.service;

import java.io.Serializable;
import org.apache.shiro.session.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.panjia.common.core.session.OnlineSession;
import com.panjia.common.utils.StringUtils;
import com.panjia.system.domain.SysUserOnline;
import com.panjia.framework.shiro.session.OnlineSessionFactory;
import com.panjia.system.service.ISysUserOnlineService;

/**
 * 会话db操作处理
 * 
 * @author panjia
 */
@Component
public class SysShiroService
{
    @Autowired
    private ISysUserOnlineService onlineService;

    @Autowired
    private OnlineSessionFactory onlineSessionFactory;

    /**
     * 删除会话
     *
     * @param onlineSession 会话信息
     */
    public void deleteSession(OnlineSession onlineSession)
    {
        onlineService.deleteOnlineById(String.valueOf(onlineSession.getId()));
    }

    /**
     * 获取会话信息
     *
     * @param sessionId
     * @return
     */
    public Session getSession(Serializable sessionId)
    {
        SysUserOnline userOnline = onlineService.selectOnlineById(String.valueOf(sessionId));
        return StringUtils.isNull(userOnline) ? null : createSession(userOnline);
    }

    public Session createSession(SysUserOnline userOnline)
    {
        return onlineSessionFactory.createSession(userOnline);
    }
}
