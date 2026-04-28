package com.bdic.model;

import java.io.Serializable;

/**
 * 登录/注册请求载荷。
 *
 * <p>该对象通过 TLS 通道传输，服务端收到后只保存密码摘要，不落库明文密码。</p>
 */
public class LoginRequest implements Serializable {
    /** Java 序列化版本号，保证对象流反序列化时结构兼容。 */
    private static final long serialVersionUID = 1L;

    /** 登录或注册时输入的用户名。 */
    private final String username;
    /** 登录或注册时输入的明文密码，只在 TLS 通道内临时传输。 */
    private final String password;

    /**
     * 构造认证请求。
     *
     * @param username 用户名。
     * @param password 明文密码；服务端收到后会立即转成加盐摘要。
     */
    public LoginRequest(String username, String password) {
        this.username = username;
        this.password = password;
    }

    /** 返回用户名。 */
    public String getUsername() {
        return username;
    }

    /** 返回明文密码，仅供服务端认证流程使用。 */
    public String getPassword() {
        return password;
    }
}
