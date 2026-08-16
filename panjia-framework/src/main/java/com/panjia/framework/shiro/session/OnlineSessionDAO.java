package com.panjia.framework.shiro.session;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Date;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.UnknownSessionException;
import org.apache.shiro.session.mgt.eis.EnterpriseCacheSessionDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.panjia.common.core.session.OnlineSession;
import com.panjia.common.enums.OnlineStatus;
import com.panjia.common.utils.AddressUtils;
import com.panjia.common.utils.StringUtils;
import com.panjia.common.utils.spring.SpringUtils;
import com.panjia.framework.manager.AsyncManager;
import com.panjia.framework.manager.factory.AsyncFactory;
import com.panjia.framework.shiro.service.SysShiroService;
import com.panjia.system.domain.SysUserOnline;
import com.panjia.system.service.ISysUserOnlineService;

/**
 * 针对自定义的ShiroSession的db操作
 * 
 * @author panjia
 */
public class OnlineSessionDAO extends EnterpriseCacheSessionDAO
{
    private static final Logger log = LoggerFactory.getLogger(OnlineSessionDAO.class);

    /**
     * 同步session到数据库的周期 单位为毫秒（默认1分钟）
     */
    @Value("${shiro.session.dbSyncPeriod}")
    private int dbSyncPeriod;

    /**
     * 上次同步数据库的时间戳
     */
    private static final String LAST_SYNC_DB_TIMESTAMP = OnlineSessionDAO.class.getName() + "LAST_SYNC_DB_TIMESTAMP";

    @Autowired
    private SysShiroService sysShiroService;

    public OnlineSessionDAO()
    {
        super();
    }

    public OnlineSessionDAO(long expireTime)
    {
        super();
    }

    /**
     * 创建新会话后，除了写入 Shiro EhCache 缓存，**同步**落库一次，保证登录响应返回前端之前，
     * DB 里已经有对应的 session 记录。
     * <p>
     * 背景：RuoYi 原始设计由 {@code SyncOnlineSessionFilter}（挂在 {@code /** → ...,syncOnlineSession} 链尾）
     * 在每个请求结束时调用 {@link #syncToDb(OnlineSession)} 写 DB。
     * 但如果登录发生在 {@code anon} 链（例如钉钉免登 {@code POST /dingtalk/login}），该链不会执行 syncOnlineSessionFilter，
     * 导致登录后的新 session 只存在于 EhCache 缓存而未落库。
     * 下一次 302 回跳 {@code /index} 时，若缓存尚未可见、缓存项已过期或被 session validation 清理，
     * 就会触发 {@link UnknownSessionException There is no session with id [xxx]}。
     * <p>
     * 之前做过异步提交的修复（{@code AsyncManager.me().execute(syncSessionToDb(...))}），
     * 但异步任务 + ScheduledExecutorService 10ms 延迟 + 线程池繁忙 + ObjectOutputStream 序列化耗时，
     * 在钉钉 PC 客户端 WKWebView 快跳 302 的场景下仍可能来不及写 DB，
     * 所以这里改为 doCreate **同步**落库，代价是每次 session 创建多一次 DB upsert，
     * 换来「anon 链 SSO 登录」场景的稳定性。后续正常请求的更新仍走 syncToDb 的节流机制。
     * <p>
     * 如果同步落库失败（DB 抖动 / Serialize 失败），fallback 到异步再试一次，不让登录流程失败。
     */
    @Override
    protected Serializable doCreate(Session session)
    {
        Serializable id = super.doCreate(session);
        if (session instanceof OnlineSession onlineSession)
        {
            long t0 = 0L;
            if (log.isDebugEnabled()) { t0 = System.nanoTime(); }
            boolean syncOk = false;
            try
            {
                saveOnlineNow(onlineSession);
                syncOk = true;
            }
            catch (Exception e)
            {
                log.warn("[OnlineSessionDAO] 创建 session id={} 同步落库失败，回退异步：{}",
                        id, e.getMessage());
            }
            if (!syncOk)
            {
                try
                {
                    AsyncManager.me().execute(AsyncFactory.syncSessionToDb(onlineSession));
                }
                catch (Exception ignore) { /* 兜底再失败就交给 SyncOnlineSessionFilter 在后续请求补写 */ }
            }
            if (log.isDebugEnabled())
            {
                long cost = (System.nanoTime() - t0) / 1000_000L;
                log.debug("[OnlineSessionDAO] session id={} 初次落库完成，同步={}，耗时={}ms",
                        id, syncOk ? "Y" : "N", cost);
            }
        }
        return id;
    }

    /**
     * 同步把一个 OnlineSession 立即 upsert 到 sys_user_online。
     * <p>
     * 字段映射与 {@link AsyncFactory#syncSessionToDb(OnlineSession)} 保持一致：
     * - OnlineSession 的 host/browser/os 由 {@link OnlineSessionFactory#createSession(SessionContext)}
     *   在 createSession 阶段就已经填好（从 HttpServletRequest 的 User-Agent / IP 解析），所以这里直接取用，
     *   不需要再做二次解析。OnlineSession 没有 getUserAgent() 方法，也没有 parseOsAndBrowser 工具方法，
     *   不要尝试去"兜底重算"，保持和 AsyncFactory 一致即可。
     * - SysUserOnline.setStatus 形参是 {@link OnlineStatus} 枚举，不是 String。
     * - 如果 host 为空（非常早期创建、非 Web 上下文），fallback 到 127.0.0.1，保持和 getRealAddressByIP 默认值一致。
     * - browser/os 为空时不再兜底，按原值（空字符串）写入即可；SyncOnlineSessionFilter 在后续请求刷新时会覆盖。
     */
    private void saveOnlineNow(OnlineSession session)
    {
        SysUserOnline online = new SysUserOnline();
        online.setSessionId(String.valueOf(session.getId()));
        online.setDeptName(session.getDeptName());
        online.setLoginName(session.getLoginName());
        online.setStartTimestamp(session.getStartTimestamp());
        online.setLastAccessTime(session.getLastAccessTime());
        online.setExpireTime(session.getTimeout());
        String host = StringUtils.isEmpty(session.getHost()) ? "127.0.0.1" : session.getHost();
        online.setIpaddr(host);
        online.setLoginLocation(AddressUtils.getRealAddressByIP(host));
        online.setBrowser(session.getBrowser());
        online.setOs(session.getOs());
        online.setStatus(session.getStatus() != null ? session.getStatus() : OnlineStatus.on_line);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos))
        {
            oos.writeObject(session);
            oos.flush();
            online.setSessionData(bos.toByteArray());
        }
        catch (Exception e)
        {
            // 序列化失败不影响 DB 主数据落库，仅记 debug 日志
            if (log.isDebugEnabled()) { log.debug("[OnlineSessionDAO] create 阶段 serialize session 失败：{}", e.getMessage()); }
            online.setSessionData(null);
        }
        // 直接从 Spring 容器拿 onlineService，避免 SysShiroService 多一层间接
        ISysUserOnlineService svc = sysShiroService != null && sysShiroService.onlineService() != null
                ? sysShiroService.onlineService()
                : SpringUtils.getBean(ISysUserOnlineService.class);
        svc.saveOnline(online);
    }

    /**
     * 根据会话ID获取会话
     * <p>
     * {@link EnterpriseCacheSessionDAO#readSession(Serializable)} 读缓存 miss 后直接调用 doReadSession，
     * 如果 DB miss（典型：上面异步任务还没执行，或 EhCache 缓存跨线程短暂不可见），
     * 这里自旋最多 40ms / 3 次重读 DB。仍 miss 再返回 null，让上层抛 UnknownSessionException，
     * 但绝大多数 SSO 302 快跳场景下，3 次重查就能命中。
     */
    @Override
    protected Session doReadSession(Serializable sessionId)
    {
        Session s = sysShiroService.getSession(sessionId);
        if (s != null) { return s; }

        for (int i = 1; i <= 3; i++)
        {
            try { Thread.sleep(10L * i); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
            s = sysShiroService.getSession(sessionId);
            if (s != null)
            {
                if (log.isDebugEnabled()) { log.debug("[OnlineSessionDAO] readSession id={} 第{}次重读命中", sessionId, i); }
                return s;
            }
        }
        return null;
    }

    /**
     * 覆盖父类 {@link org.apache.shiro.session.mgt.eis.AbstractSessionDAO#readSession(Serializable)}。
     * <p>
     * <b>关键：必须先走 super.readSession()（CachingSessionDAO，缓存优先），绝不能直接读 DB。</b>
     * <p>
     * 背景：登录时 {@code subject.login()} 的执行顺序是：doCreate（此时把 session 序列化落库）→
     * 登录成功后才把 principals/authenticated 写入 session（只更新 EhCache 缓存 + 异步/节流更新 DB）。
     * 如果 readSession 绕过缓存直接读 DB 的 session_data 快照，拿到的是<b>创建时刻的空壳</b>
     * （没有登录态），authc 过滤器判定未登录 → /index 被踢回 /login。表象：登录明明成功、
     * Cookie 也正确回传，但 2 秒后请求就被 302，钉钉 WKWebView 跟着超时断连报 ConnectionLost。
     * <p>
     * super.readSession 的行为（CachingSessionDAO）：先查 EhCache 活缓存 → miss 时调
     * {@link #doReadSession}（DB + 自旋重试）。缓存里的活 session 带完整登录态，一切正常。
     * <p>
     * 对查不到的情况返回 null（不抛 UnknownSessionException）：
     * 登出/免登换会话后，filter 清理阶段可能拿已删除的旧 sessionId 读 session，
     * 抛异常会产生大量 ERROR 日志；返回 null 时上层按「无会话/未登录」标准流程处理，行为等价。
     */
    @Override
    public Session readSession(Serializable sessionId) throws UnknownSessionException
    {
        try
        {
            return super.readSession(sessionId);
        }
        catch (UnknownSessionException e)
        {
            if (log.isDebugEnabled())
            {
                log.debug("[OnlineSessionDAO] readSession id={} 不存在（可能是旧session已被清理），返回null视为无会话",
                        sessionId);
            }
            return null;
        }
    }

    @Override
    public void update(Session session) throws UnknownSessionException
    {
        super.update(session);
    }

    /**
     * 更新会话；如更新会话最后访问时间/停止会话/设置超时时间/设置移除属性等会调用
     */
    public void syncToDb(OnlineSession onlineSession)
    {
        Date lastSyncTimestamp = (Date) onlineSession.getAttribute(LAST_SYNC_DB_TIMESTAMP);
        if (lastSyncTimestamp != null)
        {
            boolean needSync = true;
            long deltaTime = onlineSession.getLastAccessTime().getTime() - lastSyncTimestamp.getTime();
            if (deltaTime < dbSyncPeriod * 60 * 1000)
            {
                // 时间差不足 无需同步
                needSync = false;
            }
            // isGuest = true 访客
            boolean isGuest = onlineSession.getUserId() == null || onlineSession.getUserId() == 0L;

            // session 数据变更了 同步
            if (!isGuest && onlineSession.isAttributeChanged())
            {
                needSync = true;
            }

            if (!needSync)
            {
                return;
            }
        }
        // 更新上次同步数据库时间
        onlineSession.setAttribute(LAST_SYNC_DB_TIMESTAMP, onlineSession.getLastAccessTime());
        // 更新完后 重置标识
        if (onlineSession.isAttributeChanged())
        {
            onlineSession.resetAttributeChanged();
        }
        AsyncManager.me().execute(AsyncFactory.syncSessionToDb(onlineSession));
    }

    /**
     * 当会话过期/停止（如用户退出时）属性等会调用
     */
    @Override
    protected void doDelete(Session session)
    {
        OnlineSession onlineSession = (OnlineSession) session;
        if (null == onlineSession)
        {
            return;
        }
        onlineSession.setStatus(OnlineStatus.off_line);
        sysShiroService.deleteSession(onlineSession);
    }
}
