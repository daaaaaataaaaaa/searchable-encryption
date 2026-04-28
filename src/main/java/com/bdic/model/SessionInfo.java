package com.bdic.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录成功后返回给客户端的会话信息。
 *
 * <p>当前客户端与服务端保持长连接，sessionId 主要用于服务端会话管理和界面调试扩展。</p>
 */
public class SessionInfo implements Serializable {
    /** Java 序列化版本号，保证会话信息在对象流中保持兼容。 */
    @Serial
    private static final long serialVersionUID = 1L;

    /** 服务端生成的随机会话 ID。 */
    private final String sessionId;
    /** 当前会话所属用户名。 */
    private final String username;
    /** 会话过期时间，使用客户端本地时区展示。 */
    private final LocalDateTime expiresAt;

    /**
     * 构造登录成功后返回给客户端的会话信息。
     */
    public SessionInfo(String sessionId, String username, LocalDateTime expiresAt) {
        this.sessionId = sessionId;
        this.username = username;
        this.expiresAt = expiresAt;
    }

    /** 返回服务端会话 ID。 */
    public String getSessionId() {
        return sessionId;
    }

    /** 返回当前登录用户名。 */
    public String getUsername() {
        return username;
    }

    /** 返回会话过期时间。 */
    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
