package com.bdic.admin;

import com.bdic.crypto.ClientKeyManager;
import com.bdic.model.ServerResponse;
import com.bdic.model.SessionInfo;
import com.bdic.net.SecureSocketProvider;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理端桌面客户端入口。
 *
 * <p>职责：窗口入口、Tab 装配、全局状态协调与连接生命周期管理。</p>
 */
public class AdminClientApp extends JFrame {

    /** 客户端默认连接本机服务端。 */
    private static final String HOST = "127.0.0.1";
    /** 客户端与服务端约定的 TLS 监听端口。 */
    private static final int PORT = 12345;
    /** 嵌入式服务端启动后最多重试连接次数。 */
    private static final int EMBEDDED_SERVER_RETRIES = 20;
    /** 每次等待嵌入式服务端就绪的间隔。 */
    private static final long EMBEDDED_SERVER_RETRY_DELAY_MS = 300L;
    /** 自动抽取文本关键词的最大文件大小，避免大文件上传时卡住界面。 */
    private static final long MAX_AUTOMATIC_TEXT_KEYWORD_BYTES = 10L * 1024 * 1024;
    /** 主窗口基础宽度，用于 UI 缩放管理器计算比例。 */
    private static final int WINDOW_BASE_WIDTH = 760;
    /** 主窗口基础高度，用于 UI 缩放管理器计算比例。 */
    private static final int WINDOW_BASE_HEIGHT = 560;

    /** 负责加载或创建当前用户本地密钥。 */
    private final ClientKeyManager keyManager = new ClientKeyManager();

    /** 当前登录用户名。 */
    private String currentUsername;
    /** 当前用户的 DES 密钥和 PEKS 搜索公私钥。 */
    private ClientKeyManager.KeyBundle keyBundle;

    /** 与服务端建立的 TLS socket。 */
    private Socket socket;
    /** 发往服务端的对象输出流。 */
    private ObjectOutputStream out;
    /** 接收服务端响应的对象输入流。 */
    private ObjectInputStream in;

    /** 封装协议读写的客户端服务。 */
    private DocumentServiceClient serviceClient;
    /** 负责加密、解密、索引构建等本地文档操作。 */
    private DocumentOperationService operationService;
    /** 统一管理所有页面后台任务的忙碌状态。 */
    private UiBusyStateManager busyStateManager;

    /** 上传页控制器。 */
    private UploadPanelController uploadController;
    /** 搜索页控制器。 */
    private SearchPanelController searchController;
    /** 文档管理页控制器。 */
    private DocumentsPanelController documentsController;

    /** 顶部退出登录按钮，后台任务运行时会被禁用。 */
    private JButton logoutButton;

    /**
     * 构造客户端主窗口。
     *
     * <p>启动顺序：连接服务端、完成登录/注册、加载本地密钥、创建三个业务页面。</p>
     */
    public AdminClientApp() {
        try {
            connectToServer();
            serviceClient = new DocumentServiceClient(out, in);
            if (!showAuthenticationDialog()) {
                closeConnection();
                dispose();
                return;
            }
            operationService = new DocumentOperationService(MAX_AUTOMATIC_TEXT_KEYWORD_BYTES);
            createUI();
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Failed to initialize client: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 连接 TLS 服务端；如果本机没有服务端，则自动启动嵌入式服务端后重试。
     */
    private void connectToServer() throws Exception {
        try {
            openConnection();
            return;
        } catch (Exception firstFailure) {
            if (!isConnectionRefused(firstFailure)) {
                throw firstFailure;
            }
            System.out.println("No TLS server found on " + HOST + ":" + PORT + ". Starting embedded server...");
        }

        Server.startEmbedded();
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= EMBEDDED_SERVER_RETRIES; attempt++) {
            try {
                // 嵌入式服务端需要一点时间完成数据库初始化和端口监听。
                Thread.sleep(EMBEDDED_SERVER_RETRY_DELAY_MS);
                openConnection();
                return;
            } catch (Exception retryFailure) {
                lastFailure = retryFailure;
            }
        }

        throw new IOException("Embedded server did not become ready on " + HOST + ":" + PORT, lastFailure);
    }

    /**
     * 建立 TLS socket，并在其上创建对象输入输出流。
     */
    private void openConnection() throws Exception {
        socket = SecureSocketProvider.createClientSocket(HOST, PORT);
        out = new ObjectOutputStream(socket.getOutputStream());
        in = new ObjectInputStream(socket.getInputStream());
        System.out.println("Connected to TLS server at " + HOST + ":" + PORT);
    }

    /**
     * 判断异常链中是否包含连接被拒绝，用于区分“服务端未启动”和其它连接错误。
     */
    private boolean isConnectionRefused(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConnectException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 显示登录/注册对话框，并在成功后加载当前用户密钥。
     */
    private boolean showAuthenticationDialog() throws Exception {
        JTextField usernameField = new JTextField();
        JPasswordField passwordField = new JPasswordField();
        Object[] message = {
                "Username:", usernameField,
                "Password:", passwordField
        };

        while (true) {
            Object[] options = {"Login", "Register", "Cancel"};
            int choice = JOptionPane.showOptionDialog(
                    this,
                    message,
                    "Authentication",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.PLAIN_MESSAGE,
                    null,
                    options,
                    options[0]
            );

            if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) {
                return false;
            }

            String username = usernameField.getText().trim();
            String password = new String(passwordField.getPassword());
            if (username.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Username and password are required.", "Warning", JOptionPane.WARNING_MESSAGE);
                continue;
            }

            // 按用户选择调用注册或登录接口，服务端成功后会返回会话信息。
            ServerResponse response = (choice == 1)
                    ? serviceClient.register(username, password)
                    : serviceClient.login(username, password);
            showResponse(response, "Authentication");
            if (!response.isSuccess()) {
                continue;
            }

            currentUsername = username;
            if (response.getData() instanceof SessionInfo sessionInfo) {
                currentUsername = sessionInfo.getUsername();
            }

            // 用户密钥只保存在客户端本地，服务端永远拿不到 DES 密钥和 PEKS 私钥。
            keyBundle = keyManager.loadOrCreate(currentUsername);
            return true;
        }
    }

    /**
     * 创建主窗口 UI，并把上传、搜索、文档管理三个页面装配进 Tab。
     */
    private void createUI() {
        setTitle("Searchable Encryption System - Client");
        setSize(WINDOW_BASE_WIDTH, WINDOW_BASE_HEIGHT);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // 关闭窗口时先向服务端注销会话，再释放 socket。
                logoutAndExit(false);
            }
        });

        JLabel userInfoLabel = new JLabel("Current User: " + currentUsername);
        userInfoLabel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        logoutButton = new JButton("Logout");
        logoutButton.addActionListener(e -> logoutAndExit(true));
        UiComponentFactory.styleDangerButton(logoutButton);

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.add(userInfoLabel, BorderLayout.CENTER);
        headerPanel.add(logoutButton, BorderLayout.EAST);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        add(headerPanel, BorderLayout.NORTH);

        // 三个页面共享同一条服务端连接、同一组本地密钥和同一个文档操作服务。
        uploadController = new UploadPanelController(this, serviceClient, operationService, keyBundle, this::refreshDocuments);
        searchController = new SearchPanelController(this, serviceClient, operationService, keyBundle);
        documentsController = new DocumentsPanelController(this, serviceClient, operationService, keyBundle);

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Upload", uploadController.createPanel());
        tabbedPane.addTab("Search", searchController.createPanel());
        tabbedPane.addTab("Documents", documentsController.createPanel());
        add(tabbedPane, BorderLayout.CENTER);

        busyStateManager = new UiBusyStateManager(
                this,
                uploadController.getStatusLabel(),
                uploadController.getProgressBar(),
                searchController.getStatusLabel(),
                searchController.getProgressBar(),
                documentsController.getStatusLabel(),
                documentsController.getProgressBar()
        );

        uploadController.setBusyStateManager(busyStateManager);
        searchController.setBusyStateManager(busyStateManager);
        documentsController.setBusyStateManager(busyStateManager);

        // 把所有会触发网络或文件操作的控件交给忙碌状态管理器统一禁用。
        List<JComponent> busySensitive = new ArrayList<>();
        busySensitive.addAll(uploadController.getBusySensitiveComponents());
        busySensitive.addAll(searchController.getBusySensitiveComponents());
        busySensitive.addAll(documentsController.getBusySensitiveComponents());
        busySensitive.add(logoutButton);
        busyStateManager.registerBusySensitiveComponents(busySensitive);

        UiScaleManager.install(this, WINDOW_BASE_WIDTH, WINDOW_BASE_HEIGHT);
        refreshDocuments();
    }

    /**
     * 刷新文档列表。上传成功后也会调用它让 Documents 页保持同步。
     */
    private void refreshDocuments() {
        if (documentsController != null) {
            documentsController.refreshDocuments();
        }
    }

    /**
     * 以统一弹窗展示服务端响应。
     */
    private void showResponse(ServerResponse response, String title) {
        JOptionPane.showMessageDialog(
                this,
                response.getMessage(),
                title,
                response.isSuccess() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE
        );
    }

    /**
     * 注销当前会话并退出程序。
     */
    private void logoutAndExit(boolean showDialog) {
        boolean forceExit = false;
        if (busyStateManager != null && busyStateManager.isBusy()) {
            Object[] options = {"Continue Waiting", "Force Exit"};
            int choice = JOptionPane.showOptionDialog(
                    this,
                    "An operation is still in progress.\nForce exiting will interrupt the current task. Continue?",
                    "Operation In Progress",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    options,
                    options[0]
            );
            if (choice != 1) {
                return;
            }
            forceExit = true;
        }
        try {
            if (!forceExit && serviceClient != null) {
                // 即使服务端注销失败，finally 中也会关闭本地连接并退出。
                ServerResponse response = serviceClient.logout();
                if (showDialog) {
                    showResponse(response, "Logout");
                }
            }
        } catch (Exception e) {
            if (showDialog) {
                JOptionPane.showMessageDialog(this, "Logout failed: " + e.getMessage(), "Warning", JOptionPane.WARNING_MESSAGE);
            }
        } finally {
            closeConnection();
            dispose();
            System.exit(0);
        }
    }

    /**
     * 关闭本地 socket，忽略关闭阶段的异常。
     */
    private void closeConnection() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Swing 程序入口：安装 FlatLaf 主题后在事件派发线程创建主窗口。
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            FlatLightLaf.setup();
            AdminClientApp clientApp = new AdminClientApp();
            if (clientApp.currentUsername != null) {
                clientApp.setVisible(true);
            }
        });
    }
}
