package com.bdic;

import com.bdic.net.SecureSocketProvider;
import junit.framework.TestCase;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * TLS Socket 工厂测试。
 */
public class SecureSocketProviderTest extends TestCase {

    /**
     * 验证使用同一内置开发证书创建的服务端和客户端可以完成握手并交换数据。
     */
    public void testTlsClientAndServerCanExchangeData() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket serverSocket = SecureSocketProvider.createServerSocket(0)) {
            // 后台线程模拟服务端：接收一行文本，再回写 echo 响应。
            Future<String> serverResult = executor.submit(() -> {
                try (Socket accepted = serverSocket.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(accepted.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(accepted.getOutputStream(), true, StandardCharsets.UTF_8)) {
                    String line = reader.readLine();
                    writer.println("echo:" + line);
                    return line;
                }
            });

            // 客户端连接随机端口，发送 ping 并校验服务端回声。
            try (Socket clientSocket = SecureSocketProvider.createClientSocket("127.0.0.1", serverSocket.getLocalPort());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                writer.println("ping");
                assertEquals("echo:ping", reader.readLine());
            }

            assertEquals("ping", serverResult.get());
        } finally {
            // 测试结束时关闭线程池，防止后台线程影响测试进程退出。
            executor.shutdownNow();
        }
    }
}
