package com.bdic.admin;

import com.bdic.db.DatabaseManager;
import com.bdic.db.EncryptedDataRepository;
import com.bdic.db.UserRepository;
import com.bdic.model.DocumentRequest;
import com.bdic.model.DocumentSummary;
import com.bdic.model.EncryptedData;
import com.bdic.model.LoginRequest;
import com.bdic.model.NetworkMessage;
import com.bdic.model.ServerResponse;
import com.bdic.model.SessionInfo;
import com.bdic.net.SecureSocketProvider;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务端入口。
 *
 * <p>负责启动 TLS 监听、维护登录会话，并处理客户端发来的注册、登录、上传、搜索、
 * 下载、删除等请求。AdminClientApp 可以通过 startEmbedded() 在同一 JVM 内启动该服务端。</p>
 */
public class Server {

    /** 服务端监听端口，客户端也使用同一个端口连接。 */
    private static final int PORT = 12345;
    /** 会话超时时长，可通过 se.session.timeout.minutes 系统属性覆盖。 */
    private static final Duration SESSION_TIMEOUT = Duration.ofMinutes(
            Long.getLong("se.session.timeout.minutes", 30)
    );
    /** 内存会话表，key 是 sessionId。 */
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    /** 嵌入式服务端是否已启动，防止客户端重复拉起多个监听线程。 */
    private static final AtomicBoolean EMBEDDED_SERVER_STARTED = new AtomicBoolean(false);

    /** 加密文档仓储，负责文档和关键词索引持久化。 */
    private final EncryptedDataRepository repository;
    /** 用户仓储，负责注册和认证。 */
    private final UserRepository userRepository;

    /**
     * 初始化数据库结构，并创建服务端依赖的仓储对象。
     */
    public Server() {
        DatabaseManager databaseManager = new DatabaseManager();
        databaseManager.initialize();
        this.repository = new EncryptedDataRepository(databaseManager);
        this.userRepository = new UserRepository(databaseManager);
    }

    /**
     * 启动阻塞式服务端监听循环。该方法适合单独运行服务端或在后台线程中运行。
     */
    public void start() {
        try (ServerSocket serverSocket = SecureSocketProvider.createServerSocket(PORT)) {
            System.out.println("TLS server is listening on port " + PORT);

            while (true) {
                // 每次等待新连接前顺手清理过期会话，成本低且无需额外调度线程。
                cleanupExpiredSessions();
                Socket clientSocket = serverSocket.accept();
                System.out.println("New TLS client connected: " + clientSocket.getInetAddress());
                new ClientHandler(clientSocket).start();
            }
        } catch (Exception e) {
            System.err.println("Server exception: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 启动嵌入式服务端。若已经启动过，则直接返回，避免重复占用端口。
     */
    public static void startEmbedded() {
        if (!EMBEDDED_SERVER_STARTED.compareAndSet(false, true)) {
            return;
        }

        Thread serverThread = new Thread(() -> {
            try {
                new Server().start();
            } catch (Exception e) {
                EMBEDDED_SERVER_STARTED.set(false);
                throw e;
            }
        }, "searchable-encryption-embedded-server");
        // 嵌入式服务端随客户端进程退出而退出。
        serverThread.setDaemon(true);
        serverThread.start();
    }

    /**
     * 清理已过期 session，避免长时间运行时会话表无限增长。
     */
    private static void cleanupExpiredSessions() {
        Instant now = Instant.now();
        SESSIONS.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(now));
    }

    /**
     * 单个客户端连接的处理线程。
     */
    private class ClientHandler extends Thread {
        /** 当前连接对应的底层 socket。 */
        private final Socket socket;
        /** 当前连接绑定的 sessionId，登录后才有值。 */
        private String currentSessionId;
        /** 当前连接绑定的用户名，登录后才有值。 */
        private String currentUsername;

        /**
         * 为一个客户端连接创建独立处理线程。
         */
        private ClientHandler(Socket socket) {
            this.socket = socket;
        }

        /**
         * 持续读取客户端消息，并按消息类型分发到具体业务逻辑。
         */
        @Override
        public void run() {
            try (ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                 ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

                while (true) {
                    // 客户端和服务端都通过 NetworkMessage 包装请求/响应对象。
                    NetworkMessage message = (NetworkMessage) in.readObject();
                    if (message == null) {
                        break;
                    }

                    try {
                        // 认证类请求可直接处理；文档类请求先检查当前连接是否已登录。
                        switch (message.getType()) {
                            case REGISTER:
                                handleRegister(message, out);
                                break;

                            case LOGIN:
                                handleLogin(message, out);
                                break;

                            case LOGOUT:
                                closeSession();
                                writeResponse(out, true, "Logged out.", null);
                                break;

                            case UPLOAD:
                                if (!ensureAuthenticated(out)) {
                                    break;
                                }
                                EncryptedData data = (EncryptedData) message.getPayload();
                                // 服务端保存的仍是密文内容和关键词密文，不接触客户端明文。
                                repository.save(currentUsername, data);
                                System.out.println("Stored document " + data.getDocId() + " for " + currentUsername);
                                writeResponse(out, true, "Upload succeeded.", null);
                                break;

                            case SEARCH:
                                if (!ensureAuthenticated(out)) {
                                    break;
                                }
                                byte[] trapdoor = (byte[]) message.getPayload();
                                // 搜索只把 trapdoor 交给仓储层执行 PEKS 测试，服务端不接触明文关键词。
                                List<EncryptedData> matchedData = repository.searchByTrapdoor(currentUsername, trapdoor);
                                writeResponse(out, true, "Search completed.", matchedData);
                                break;

                            case LIST_DOCUMENTS:
                                if (!ensureAuthenticated(out)) {
                                    break;
                                }
                                List<DocumentSummary> documentSummaries = repository.listDocuments(currentUsername);
                                writeResponse(out, true, "Document list loaded.", documentSummaries);
                                break;

                            case DOWNLOAD_DOCUMENT:
                                if (!ensureAuthenticated(out)) {
                                    break;
                                }
                                DocumentRequest downloadRequest = (DocumentRequest) message.getPayload();
                                // 查询时带上当前用户名，避免用户通过 docId 下载别人的文档。
                                EncryptedData document = repository.findByOwnerAndDocId(currentUsername, downloadRequest.getDocId());
                                if (document == null) {
                                    writeResponse(out, false, "Document not found.", null);
                                } else {
                                    writeResponse(out, true, "Download ready.", document);
                                }
                                break;

                            case DELETE_DOCUMENT:
                                if (!ensureAuthenticated(out)) {
                                    break;
                                }
                                DocumentRequest deleteRequest = (DocumentRequest) message.getPayload();
                                boolean deleted = repository.deleteByOwnerAndDocId(currentUsername, deleteRequest.getDocId());
                                writeResponse(out, deleted, deleted ? "Delete succeeded." : "Document not found.", null);
                                break;

                            default:
                                writeResponse(out, false, "Unsupported message type: " + message.getType(), null);
                        }
                    } catch (Exception e) {
                        // 统一把内部异常转换成客户端可读信息，同时服务端保留完整堆栈。
                        String clientMessage = buildClientSafeErrorMessage(message.getType(), e);
                        System.err.println("Client request failed: " + clientMessage);
                        e.printStackTrace();
                        writeResponse(out, false, clientMessage, null);
                    }
                }
            } catch (java.io.EOFException e) {
                System.out.println("Client disconnected.");
            } catch (Exception e) {
                System.err.println("Client handler exception: " + e.getMessage());
                e.printStackTrace();
            } finally {
                closeSession();
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * 注册成功后立即创建登录会话。
         */
        private void handleRegister(NetworkMessage message, ObjectOutputStream out) throws IOException {
            LoginRequest loginRequest = (LoginRequest) message.getPayload();
            // 用户名作为主键，仓储会把重复用户名转换成 false。
            boolean created = userRepository.register(loginRequest.getUsername(), loginRequest.getPassword());
            if (!created) {
                writeResponse(out, false, "Username already exists.", null);
                return;
            }

            openSession(loginRequest.getUsername(), out, "Registration succeeded.");
        }

        /**
         * 校验用户名密码，成功后创建登录会话。
         */
        private void handleLogin(NetworkMessage message, ObjectOutputStream out) throws IOException {
            LoginRequest loginRequest = (LoginRequest) message.getPayload();
            // 服务端只用密码摘要校验，不会把明文密码写入数据库。
            boolean authenticated = userRepository.authenticate(loginRequest.getUsername(), loginRequest.getPassword());
            if (!authenticated) {
                writeResponse(out, false, "Invalid username or password.", null);
                return;
            }

            openSession(loginRequest.getUsername(), out, "Login succeeded.");
        }

        /**
         * 创建新的服务端 session，并把会话信息返回给客户端。
         */
        private void openSession(String username, ObjectOutputStream out, String message) throws IOException {
            // 同一连接重新登录时先关闭旧 session，避免一个连接绑定多个身份。
            closeSession();
            Session session = Session.create(username);
            SESSIONS.put(session.sessionId, session);
            currentSessionId = session.sessionId;
            currentUsername = username;
            writeResponse(out, true, message, session.toInfo());
        }

        /**
         * 校验当前连接是否已登录、session 是否仍有效；有效时顺便刷新过期时间。
         */
        private boolean ensureAuthenticated(ObjectOutputStream out) throws IOException {
            if (currentSessionId == null || currentUsername == null || currentUsername.isBlank()) {
                writeResponse(out, false, "Please log in first.", null);
                return false;
            }

            Session session = SESSIONS.get(currentSessionId);
            if (session == null || !currentUsername.equals(session.username)) {
                // 会话表中不存在或用户名不一致，都视为会话失效。
                currentSessionId = null;
                currentUsername = null;
                writeResponse(out, false, "Session is no longer valid. Please log in again.", null);
                return false;
            }

            if (session.isExpired()) {
                closeSession();
                writeResponse(out, false, "Session expired. Please log in again.", null);
                return false;
            }

            // 有效请求会滑动刷新过期时间，保持活跃用户不被中途踢下线。
            session.refresh();
            return true;
        }

        /**
         * 主动移除当前连接绑定的 session。
         */
        private void closeSession() {
            if (currentSessionId != null) {
                SESSIONS.remove(currentSessionId);
            }
            currentSessionId = null;
            currentUsername = null;
        }

        /**
         * 按统一协议向客户端写回响应。
         */
        private void writeResponse(ObjectOutputStream out, boolean success, String message, Object data) throws IOException {
            // 响应也走 NetworkMessage，便于协议层只处理一种顶层对象。
            out.writeObject(new NetworkMessage(
                    NetworkMessage.MessageType.RESPONSE,
                    new ServerResponse(success, message, data)
            ));
            out.flush();
        }
    }

    /**
     * 把服务端异常转换为客户端可展示的简洁错误信息。
     */
    private static String buildClientSafeErrorMessage(NetworkMessage.MessageType type, Exception exception) {
        Throwable rootCause = findRootCause(exception);
        String detail = rootCause.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = rootCause.getClass().getSimpleName();
        }
        if ("PacketTooBigException".equals(rootCause.getClass().getSimpleName())) {
            return inferActionLabel(type) + " failed: file is too large for the current MySQL max_allowed_packet limit. " + detail;
        }
        return inferActionLabel(type) + " failed: " + detail;
    }

    /**
     * 将消息类型转换成面向用户的操作名称。
     */
    private static String inferActionLabel(NetworkMessage.MessageType type) {
        if (type == null) {
            return "Request";
        }
        return switch (type) {
            case REGISTER -> "Registration";
            case LOGIN -> "Login";
            case LOGOUT -> "Logout";
            case UPLOAD -> "Upload";
            case SEARCH -> "Search";
            case LIST_DOCUMENTS -> "Document list";
            case DOWNLOAD_DOCUMENT -> "Download";
            case DELETE_DOCUMENT -> "Delete";
            case RESPONSE -> "Request";
        };
    }

    /**
     * 沿异常链找到最底层原因，便于输出真正失败点。
     */
    private static Throwable findRootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 服务端内存会话对象。
     */
    private static class Session {
        /** 随机生成的会话标识。 */
        private final String sessionId;
        /** 会话所属用户。 */
        private final String username;
        /** 会话过期时间，volatile 便于不同线程看到刷新后的值。 */
        private volatile Instant expiresAt;

        /**
         * 构造内部会话对象。
         */
        private Session(String sessionId, String username, Instant expiresAt) {
            this.sessionId = sessionId;
            this.username = username;
            this.expiresAt = expiresAt;
        }

        /**
         * 创建带随机 sessionId 的新会话。
         */
        private static Session create(String username) {
            return new Session(UUID.randomUUID().toString(), username, Instant.now().plus(SESSION_TIMEOUT));
        }

        /** 判断当前会话是否已过期。 */
        private boolean isExpired() {
            return expiresAt.isBefore(Instant.now());
        }

        /** 将过期时间向后顺延一个超时周期。 */
        private void refresh() {
            expiresAt = Instant.now().plus(SESSION_TIMEOUT);
        }

        /**
         * 将内部会话转换为可序列化给客户端的 DTO。
         */
        private SessionInfo toInfo() {
            LocalDateTime localExpiresAt = LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault());
            return new SessionInfo(sessionId, username, localExpiresAt);
        }
    }

}
