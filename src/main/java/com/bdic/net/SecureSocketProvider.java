package com.bdic.net;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * TLS Socket 工厂。
 *
 * <p>客户端和服务端共用项目内置的开发证书，保证本地通信走加密通道。该证书仅适合教学和本地演示，
 * 如果部署到真实环境，应替换为受信任 CA 签发的证书，并妥善管理密钥库密码。</p>
 */
public final class SecureSocketProvider {

    /** classpath 中内置开发证书的位置。 */
    private static final String KEY_STORE_RESOURCE = "/tls/searchable-encryption-dev.p12";
    /** 开发证书密钥库密码；真实环境应改为安全配置。 */
    private static final char[] KEY_STORE_PASSWORD = "changeit".toCharArray();
    /** 优先启用的 TLS 协议版本，按安全性从高到低排列。 */
    private static final String[] PREFERRED_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    /** 工具类不需要实例化。 */
    private SecureSocketProvider() {
    }

    /**
     * 创建服务端 TLS 监听 Socket。
     */
    public static ServerSocket createServerSocket(int port) throws IOException, GeneralSecurityException {
        SSLContext context = createServerContext();
        SSLServerSocketFactory factory = context.getServerSocketFactory();
        SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(port);
        // 只保留当前 JDK 支持的安全协议，避免意外启用过旧 TLS 版本。
        configureProtocols(serverSocket);
        serverSocket.setNeedClientAuth(false);
        return serverSocket;
    }

    /**
     * 创建客户端 TLS Socket，并主动完成握手。
     */
    public static Socket createClientSocket(String host, int port) throws IOException, GeneralSecurityException {
        SSLContext context = createClientContext();
        SSLSocketFactory factory = context.getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket(host, port);
        configureProtocols(socket);

        // 开启主机名校验，让客户端确认证书身份与连接目标匹配。
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        // 主动握手可以把证书或协议问题尽早暴露给调用方。
        socket.startHandshake();
        return socket;
    }

    /**
     * 服务端上下文需要加载私钥，用于向客户端证明服务端身份。
     */
    private static SSLContext createServerContext() throws IOException, GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();

        // 服务端从密钥库中加载私钥，供 TLS 握手阶段证明自己的身份。
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, KEY_STORE_PASSWORD);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    /**
     * 客户端上下文只需要信任内置证书，用于校验服务端证书。
     */
    private static SSLContext createClientContext() throws IOException, GeneralSecurityException {
        KeyStore trustStore = loadKeyStore();

        // 客户端把同一个开发证书当作信任锚，用于校验服务端证书链。
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
        return context;
    }

    /**
     * 从 classpath 读取 PKCS12 密钥库。
     */
    private static KeyStore loadKeyStore() throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream inputStream = SecureSocketProvider.class.getResourceAsStream(KEY_STORE_RESOURCE)) {
            if (inputStream == null) {
                throw new IOException("TLS key store resource not found: " + KEY_STORE_RESOURCE);
            }
            keyStore.load(inputStream, KEY_STORE_PASSWORD);
        }
        return keyStore;
    }

    /**
     * 仅启用当前 JDK 支持的 TLSv1.3/TLSv1.2。
     */
    private static void configureProtocols(SSLSocket socket) {
        socket.setEnabledProtocols(selectSupportedProtocols(socket.getSupportedProtocols()));
    }

    /**
     * 为服务端 Socket 选择可用的 TLS 协议版本。
     */
    private static void configureProtocols(SSLServerSocket socket) {
        socket.setEnabledProtocols(selectSupportedProtocols(socket.getSupportedProtocols()));
    }

    /**
     * 从偏好列表中筛出当前 JDK/平台真正支持的协议。
     */
    private static String[] selectSupportedProtocols(String[] supportedProtocols) {
        return Arrays.stream(PREFERRED_PROTOCOLS)
                .filter(protocol -> Arrays.asList(supportedProtocols).contains(protocol))
                .toArray(String[]::new);
    }
}
