package com.bdic.admin;

import com.bdic.crypto.ClientKeyManager;
import com.bdic.crypto.DESUtil;
import com.bdic.model.DocumentSummary;
import com.bdic.model.EncryptedData;
import com.bdic.model.ServerResponse;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 文档页控制器：负责文档列表、下载、删除、重建索引及其批量操作。
 */
public class DocumentsPanelController {

    /** 主窗口，用于弹窗、文件选择器和重建索引输入框挂靠。 */
    private final JFrame owner;
    /** 与服务端通信的客户端。 */
    private final DocumentServiceClient serviceClient;
    /** 本地文档解密、路径处理和索引重建服务。 */
    private final DocumentOperationService operationService;
    /** 当前用户本地密钥。 */
    private final ClientKeyManager.KeyBundle keyBundle;
    /** 全局忙碌状态管理器。 */
    private UiBusyStateManager busyStateManager;

    /** 手动输入文档 ID 的操作框。 */
    private JTextField documentActionField;
    /** 文档摘要表格。 */
    private JTable documentTable;
    /** 文档表格模型。 */
    private DefaultTableModel documentTableModel;
    /** 刷新文档列表按钮。 */
    private JButton refreshButton;
    /** 下载按钮。 */
    private JButton downloadButton;
    /** 删除按钮。 */
    private JButton deleteButton;
    /** 重建索引按钮。 */
    private JButton rebuildIndexButton;
    /** 文档页状态文本。 */
    private JLabel documentsStatusLabel;
    /** 文档页进度条。 */
    private JProgressBar documentsProgressBar;

    /** 当前表格中每一行对应的文档摘要，顺序与表格行保持一致。 */
    private final List<DocumentSummary> currentDocumentSummaries = new ArrayList<>();

    /**
     * 创建文档管理页控制器。
     */
    public DocumentsPanelController(
            JFrame owner,
            DocumentServiceClient serviceClient,
            DocumentOperationService operationService,
            ClientKeyManager.KeyBundle keyBundle
    ) {
        this.owner = owner;
        this.serviceClient = serviceClient;
        this.operationService = operationService;
        this.keyBundle = keyBundle;
    }

    /**
     * 创建文档管理页完整 UI。
     */
    public JPanel createPanel() {
        JPanel documentsPanel = new JPanel(new BorderLayout(10, 10));
        documentsPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 顶部操作栏提供刷新、按 ID 操作和批量操作入口。
        JPanel topActionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        refreshButton = new JButton("Refresh List");
        refreshButton.addActionListener(e -> refreshDocuments());
        UiComponentFactory.styleSecondaryButton(refreshButton);
        topActionsPanel.add(refreshButton);

        topActionsPanel.add(new JLabel("Doc ID:"));
        documentActionField = new JTextField(18);
        topActionsPanel.add(documentActionField);

        downloadButton = new JButton("Download");
        downloadButton.addActionListener(e -> downloadDocument());
        UiComponentFactory.stylePrimaryButton(downloadButton);
        topActionsPanel.add(downloadButton);

        deleteButton = new JButton("Delete");
        deleteButton.addActionListener(e -> deleteDocument());
        UiComponentFactory.styleDangerButton(deleteButton);
        topActionsPanel.add(deleteButton);

        rebuildIndexButton = new JButton("Rebuild Index");
        rebuildIndexButton.addActionListener(e -> rebuildDocumentIndex());
        UiComponentFactory.styleSecondaryButton(rebuildIndexButton);
        topActionsPanel.add(rebuildIndexButton);

        documentsPanel.add(UiComponentFactory.createSectionPanel("Document Actions", "Select one or more rows below or enter a Document ID to manage encrypted files.", topActionsPanel), BorderLayout.NORTH);

        // 表格只展示摘要，不直接暴露加密正文。
        documentTableModel = new DefaultTableModel(new Object[]{"Document ID", "File Name", "Type", "Size", "Keywords", "Created At"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        documentTable = new JTable(documentTableModel);
        documentTable.setRowHeight(28);
        documentTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        documentTable.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) {
                return;
            }
            // 单选时把 docId 填入操作框，多选时提示已选择数量。
            List<DocumentSummary> selectedDocuments = getSelectedDocumentSummaries();
            if (selectedDocuments.size() == 1) {
                documentActionField.setText(selectedDocuments.get(0).getDocId());
            } else if (selectedDocuments.size() > 1) {
                documentActionField.setText(selectedDocuments.size() + " selected");
            }
        });

        JScrollPane tableScrollPane = new JScrollPane(documentTable);
        documentsPanel.add(UiComponentFactory.createSectionPanel("My Documents", "Browse uploaded documents and use the actions above to manage one or multiple files.", tableScrollPane), BorderLayout.CENTER);

        documentsStatusLabel = new JLabel(" ");
        documentsStatusLabel.setForeground(new Color(75, 85, 99));
        documentsProgressBar = new JProgressBar();
        documentsProgressBar.setIndeterminate(true);
        documentsProgressBar.setVisible(false);
        documentsProgressBar.setPreferredSize(new Dimension(180, 18));

        JPanel documentsStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        documentsStatusPanel.add(documentsProgressBar);
        documentsStatusPanel.add(Box.createHorizontalStrut(8));
        documentsStatusPanel.add(documentsStatusLabel);
        documentsPanel.add(documentsStatusPanel, BorderLayout.SOUTH);
        return documentsPanel;
    }

    /** 注入忙碌状态管理器。 */
    public void setBusyStateManager(UiBusyStateManager busyStateManager) {
        this.busyStateManager = busyStateManager;
    }

    /** 返回文档页状态标签。 */
    public JLabel getStatusLabel() {
        return documentsStatusLabel;
    }

    /** 返回文档页进度条。 */
    public JProgressBar getProgressBar() {
        return documentsProgressBar;
    }

    /** 返回后台任务运行时需要禁用的文档页控件。 */
    public List<JComponent> getBusySensitiveComponents() {
        List<JComponent> components = new ArrayList<>();
        components.add(refreshButton);
        components.add(downloadButton);
        components.add(deleteButton);
        components.add(rebuildIndexButton);
        components.add(documentActionField);
        components.add(documentTable);
        return components;
    }

    /**
     * 从服务端刷新当前用户文档摘要列表。
     */
    public void refreshDocuments() {
        if (busyStateManager == null || busyStateManager.isBusy()) {
            return;
        }
        busyStateManager.setDocumentsBusy(true, "Refreshing document list...");
        // 文档列表加载放在后台执行，避免数据库或网络延迟阻塞界面。
        new SwingWorker<DocumentListTaskResult, Void>() {
            @Override
            protected DocumentListTaskResult doInBackground() {
                try {
                    return DocumentListTaskResult.success(loadDocumentSummaries());
                } catch (Exception e) {
                    return DocumentListTaskResult.failure("Failed to refresh document list: " + DocumentOperationService.describeException(e));
                }
            }

            @Override
            protected void done() {
                DocumentListTaskResult result;
                try {
                    result = get();
                } catch (Exception e) {
                    result = DocumentListTaskResult.failure("Failed to refresh document list: " + DocumentOperationService.describeException(e));
                }

                if (busyStateManager != null) {
                    busyStateManager.setDocumentsBusy(false, " ");
                }
                if (result.errorMessage() != null) {
                    clearDocumentTable();
                    JOptionPane.showMessageDialog(owner, result.errorMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                applyDocumentSummaries(result.documents());
            }
        }.execute();
    }

    /**
     * 调用服务端文档列表接口，并把反序列化结果转换为摘要列表。
     */
    private List<DocumentSummary> loadDocumentSummaries() throws Exception {
        ServerResponse response = serviceClient.listDocuments();
        if (!response.isSuccess()) {
            throw new IllegalStateException(response.getMessage());
        }
        if (!(response.getData() instanceof List<?> rawDocuments)) {
            throw new IllegalStateException("Unexpected response from server.");
        }

        List<DocumentSummary> summaries = new ArrayList<>();
        for (Object rawDocument : rawDocuments) {
            // 服务端保证列表元素类型为 DocumentSummary，这里做显式转换。
            summaries.add((DocumentSummary) rawDocument);
        }
        return summaries;
    }

    /**
     * 将文档摘要刷新到表格和本地缓存。
     */
    private void applyDocumentSummaries(List<DocumentSummary> summaries) {
        clearDocumentTable();
        for (DocumentSummary summary : summaries) {
            currentDocumentSummaries.add(summary);
            documentTableModel.addRow(new Object[]{
                    summary.getDocId(),
                    summary.getFileName(),
                    summary.getMediaType(),
                    formatFileSize(summary.getFileSize()),
                    summary.getKeywordCount(),
                    summary.getCreatedAt()
            });
        }
    }

    /**
     * 根据当前选择执行单文件下载或批量下载。
     */
    private void downloadDocument() {
        if (busyStateManager == null || busyStateManager.isBusy()) {
            return;
        }
        try {
            final List<String> targetDocIds = resolveTargetDocumentIds();
            if (targetDocIds.isEmpty()) {
                JOptionPane.showMessageDialog(owner, "Please enter a document ID.", "Warning", JOptionPane.WARNING_MESSAGE);
                return;
            }

            if (targetDocIds.size() > 1) {
                // 多选下载时先让用户选择目标文件夹，每个文件自动避免重名覆盖。
                String selectedFolder = NativeDialogHelper.chooseFolder("Select Folder to Save Downloaded Files");
                if (selectedFolder == null || selectedFolder.isBlank()) {
                    return;
                }
                final Path targetDirectory = Path.of(selectedFolder);
                busyStateManager.setDocumentsBusy(true, "Downloading " + targetDocIds.size() + " documents...");
                new SwingWorker<DocumentOperationTaskResult, String>() {
                    @Override
                    protected DocumentOperationTaskResult doInBackground() {
                        return performBatchDownload(targetDocIds, targetDirectory, status -> publish(status));
                    }

                    @Override
                    protected void process(List<String> chunks) {
                        // 批量操作过程中显示最新进度。
                        if (!chunks.isEmpty() && busyStateManager != null) {
                            busyStateManager.updateDocumentsStatus(chunks.get(chunks.size() - 1));
                        }
                    }

                    @Override
                    protected void done() {
                        finishDocumentOperation(this, "Download failed.");
                    }
                }.execute();
                return;
            }

            // 单文件下载使用系统保存对话框，让用户决定文件名和位置。
            final String docId = targetDocIds.get(0);
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setSelectedFile(new File(resolveSuggestedFileName(docId)));
            int option = fileChooser.showSaveDialog(owner);
            if (option != JFileChooser.APPROVE_OPTION) {
                return;
            }

            final Path targetPath = fileChooser.getSelectedFile().toPath();
            busyStateManager.setDocumentsBusy(true, "Downloading " + docId + "...");
            new SwingWorker<DocumentOperationTaskResult, String>() {
                @Override
                protected DocumentOperationTaskResult doInBackground() {
                    publish("Downloading " + docId + "...");
                    return performSingleDownload(docId, targetPath);
                }

                @Override
                protected void process(List<String> chunks) {
                    if (!chunks.isEmpty() && busyStateManager != null) {
                        busyStateManager.updateDocumentsStatus(chunks.get(chunks.size() - 1));
                    }
                }

                @Override
                protected void done() {
                    finishDocumentOperation(this, "Download failed.");
                }
            }.execute();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(owner, "Download failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 删除一个或多个文档。
     */
    private void deleteDocument() {
        if (busyStateManager == null || busyStateManager.isBusy()) {
            return;
        }
        try {
            final List<String> targetDocIds = resolveTargetDocumentIds();
            if (targetDocIds.isEmpty()) {
                JOptionPane.showMessageDialog(owner, "Please enter a document ID.", "Warning", JOptionPane.WARNING_MESSAGE);
                return;
            }

            // 删除不可撤销，因此先根据数量显示确认对话框。
            int confirmed = JOptionPane.showConfirmDialog(
                    owner,
                    targetDocIds.size() == 1
                            ? "Delete document " + targetDocIds.get(0) + "?"
                            : "Delete " + targetDocIds.size() + " selected documents?",
                    targetDocIds.size() == 1 ? "Delete Document" : "Batch Delete",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (confirmed != JOptionPane.OK_OPTION) {
                return;
            }

            busyStateManager.setDocumentsBusy(true, targetDocIds.size() == 1
                    ? "Deleting " + targetDocIds.get(0) + "..."
                    : "Deleting " + targetDocIds.size() + " documents...");
            new SwingWorker<DocumentOperationTaskResult, String>() {
                @Override
                protected DocumentOperationTaskResult doInBackground() {
                    return performDelete(targetDocIds, status -> publish(status));
                }

                @Override
                protected void process(List<String> chunks) {
                    if (!chunks.isEmpty() && busyStateManager != null) {
                        busyStateManager.updateDocumentsStatus(chunks.get(chunks.size() - 1));
                    }
                }

                @Override
                protected void done() {
                    finishDocumentOperation(this, "Delete failed.");
                }
            }.execute();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(owner, "Delete failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 为一个或多个文档重新生成关键词索引。
     */
    private void rebuildDocumentIndex() {
        if (busyStateManager == null || busyStateManager.isBusy()) {
            return;
        }
        try {
            final List<String> targetDocIds = resolveTargetDocumentIds();
            if (targetDocIds.isEmpty()) {
                JOptionPane.showMessageDialog(owner, "Please enter a document ID.", "Warning", JOptionPane.WARNING_MESSAGE);
                return;
            }

            // 重建索引会覆盖服务端关键词索引，执行前让用户确认。
            int confirmed = JOptionPane.showConfirmDialog(
                    owner,
                    targetDocIds.size() == 1
                            ? "Rebuild index for document " + targetDocIds.get(0) + "?"
                            : "Rebuild index for " + targetDocIds.size() + " selected documents?",
                    targetDocIds.size() == 1 ? "Rebuild Index" : "Batch Rebuild Index",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );
            if (confirmed != JOptionPane.OK_OPTION) {
                return;
            }

            busyStateManager.setDocumentsBusy(true, targetDocIds.size() == 1
                    ? "Rebuilding index for " + targetDocIds.get(0) + "..."
                    : "Rebuilding indexes for " + targetDocIds.size() + " documents...");
            new SwingWorker<DocumentOperationTaskResult, String>() {
                @Override
                protected DocumentOperationTaskResult doInBackground() {
                    return performRebuildIndex(targetDocIds, status -> publish(status));
                }

                @Override
                protected void process(List<String> chunks) {
                    if (!chunks.isEmpty() && busyStateManager != null) {
                        busyStateManager.updateDocumentsStatus(chunks.get(chunks.size() - 1));
                    }
                }

                @Override
                protected void done() {
                    finishDocumentOperation(this, "Rebuild index failed.");
                }
            }.execute();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(owner, "Rebuild index failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 按文档 ID 下载完整密文文档。
     */
    private EncryptedData downloadDocumentById(String docId) throws Exception {
        ServerResponse response = serviceClient.downloadDocument(docId);
        if (!response.isSuccess()) {
            throw new IllegalStateException(response.getMessage());
        }
        return (EncryptedData) response.getData();
    }

    /**
     * 按文档 ID 删除服务端文档。
     */
    private ServerResponse deleteDocumentById(String docId) throws Exception {
        return serviceClient.deleteDocument(docId);
    }

    /**
     * 下载文档、在客户端重建索引，再重新上传覆盖服务端索引。
     */
    private ServerResponse rebuildDocumentIndexById(String docId) throws Exception {
        EncryptedData data = downloadDocumentById(docId);
        EncryptedData rebuilt = operationService.rebuildIndex(data, keyBundle.desKey(), keyBundle.peksKey(), owner);
        return serviceClient.upload(rebuilt);
    }

    /**
     * 执行单文档下载：下载密文、客户端解密、写入目标路径。
     */
    private DocumentOperationTaskResult performSingleDownload(String docId, Path targetPath) {
        try {
            EncryptedData data = downloadDocumentById(docId);
            byte[] decryptedContent = DESUtil.decrypt(data.getEncryptedContent(), keyBundle.desKey());
            Files.write(targetPath, decryptedContent);
            return new DocumentOperationTaskResult(
                    "Download Result",
                    "File saved to: " + targetPath,
                    JOptionPane.INFORMATION_MESSAGE,
                    false
            );
        } catch (Exception exception) {
            return DocumentOperationTaskResult.error("Download failed: " + DocumentOperationService.describeException(exception));
        }
    }

    /**
     * 执行批量下载，单个文件失败不会影响后续文件。
     */
    private DocumentOperationTaskResult performBatchDownload(List<String> docIds, Path targetDirectory, Consumer<String> statusUpdater) {
        int successCount = 0;
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < docIds.size(); i++) {
            String docId = docIds.get(i);
            statusUpdater.accept("Downloading (" + (i + 1) + "/" + docIds.size() + "): " + docId);
            try {
                EncryptedData data = downloadDocumentById(docId);
                byte[] decryptedContent = DESUtil.decrypt(data.getEncryptedContent(), keyBundle.desKey());
                // 文件名冲突时自动追加序号，避免覆盖用户已有文件。
                Path targetPath = operationService.resolveUniqueChildPath(targetDirectory, operationService.defaultFileName(data));
                Files.write(targetPath, decryptedContent);
                successCount++;
            } catch (Exception exception) {
                failures.add(docId + ": " + DocumentOperationService.describeException(exception));
            }
        }
        return buildBatchOperationResult("Batch Download Result", "Download", successCount, failures, false);
    }

    /**
     * 执行批量删除。
     */
    private DocumentOperationTaskResult performDelete(List<String> docIds, Consumer<String> statusUpdater) {
        int successCount = 0;
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < docIds.size(); i++) {
            String docId = docIds.get(i);
            statusUpdater.accept("Deleting (" + (i + 1) + "/" + docIds.size() + "): " + docId);
            try {
                ServerResponse response = deleteDocumentById(docId);
                if (response.isSuccess()) {
                    successCount++;
                } else {
                    failures.add(docId + ": " + response.getMessage());
                }
            } catch (Exception exception) {
                failures.add(docId + ": " + DocumentOperationService.describeException(exception));
            }
        }
        return buildBatchOperationResult(
                docIds.size() == 1 ? "Delete Result" : "Batch Delete Result",
                "Delete",
                successCount,
                failures,
                successCount > 0
        );
    }

    /**
     * 执行批量索引重建。
     */
    private DocumentOperationTaskResult performRebuildIndex(List<String> docIds, Consumer<String> statusUpdater) {
        int successCount = 0;
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < docIds.size(); i++) {
            String docId = docIds.get(i);
            statusUpdater.accept("Rebuilding (" + (i + 1) + "/" + docIds.size() + "): " + docId);
            try {
                ServerResponse response = rebuildDocumentIndexById(docId);
                if (response.isSuccess()) {
                    successCount++;
                } else {
                    failures.add(docId + ": " + response.getMessage());
                }
            } catch (Exception exception) {
                failures.add(docId + ": " + DocumentOperationService.describeException(exception));
            }
        }
        return buildBatchOperationResult(
                docIds.size() == 1 ? "Rebuild Index" : "Batch Rebuild Result",
                "Rebuild",
                successCount,
                failures,
                successCount > 0
        );
    }

    /**
     * 汇总批量操作结果，生成统一弹窗内容。
     */
    private DocumentOperationTaskResult buildBatchOperationResult(
            String title,
            String actionLabel,
            int successCount,
            List<String> failures,
            boolean refreshDocuments
    ) {
        StringBuilder summary = new StringBuilder();
        summary.append(actionLabel).append(" finished.\n")
                .append("Success: ").append(successCount).append('\n')
                .append("Failed: ").append(failures.size());
        if (!failures.isEmpty()) {
            summary.append("\n\nFailures:\n").append(String.join("\n", failures));
        }
        return new DocumentOperationTaskResult(
                title,
                summary.toString(),
                failures.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE,
                refreshDocuments
        );
    }

    /**
     * 统一收尾下载、删除、重建索引后台任务。
     */
    private void finishDocumentOperation(SwingWorker<DocumentOperationTaskResult, String> worker, String fallbackError) {
        DocumentOperationTaskResult result;
        try {
            result = worker.get();
        } catch (Exception exception) {
            result = DocumentOperationTaskResult.error(fallbackError + " " + DocumentOperationService.describeException(exception));
        }

        if (busyStateManager != null) {
            busyStateManager.setDocumentsBusy(false, " ");
        }
        // 删除和重建索引成功后需要刷新表格，下载不需要。
        if (result.refreshDocuments()) {
            refreshDocuments();
        }
        JOptionPane.showMessageDialog(
                owner,
                result.message(),
                result.title(),
                result.messageType()
        );
    }

    /**
     * 根据表格选中行获取对应文档摘要。
     */
    private List<DocumentSummary> getSelectedDocumentSummaries() {
        List<DocumentSummary> selected = new ArrayList<>();
        if (documentTable == null) {
            return selected;
        }
        for (int selectedRow : documentTable.getSelectedRows()) {
            if (selectedRow >= 0 && selectedRow < currentDocumentSummaries.size()) {
                selected.add(currentDocumentSummaries.get(selectedRow));
            }
        }
        return selected;
    }

    /**
     * 解析当前操作目标：优先使用表格选中行，没有选中行时使用手动输入的 docId。
     */
    private List<String> resolveTargetDocumentIds() {
        List<String> selectedDocIds = getSelectedDocumentSummaries().stream()
                .map(DocumentSummary::getDocId)
                .distinct()
                .collect(Collectors.toList());
        if (!selectedDocIds.isEmpty()) {
            return selectedDocIds;
        }

        String docId = documentActionField.getText().trim();
        if (docId.isEmpty() || docId.endsWith(" selected")) {
            return List.of();
        }
        return List.of(docId);
    }

    /**
     * 为单文件下载推导默认保存文件名。
     */
    private String resolveSuggestedFileName(String docId) {
        return currentDocumentSummaries.stream()
                .filter(summary -> docId.equals(summary.getDocId()))
                .map(DocumentSummary::getFileName)
                .filter(fileName -> fileName != null && !fileName.isBlank())
                .findFirst()
                .orElse(docId);
    }

    /**
     * 清空表格和本地摘要缓存。
     */
    private void clearDocumentTable() {
        currentDocumentSummaries.clear();
        if (documentTableModel != null) {
            documentTableModel.setRowCount(0);
        }
    }

    /**
     * 把字节数格式化为 B/KB/MB 文本。
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    /**
     * 文档列表后台任务结果。
     *
     * @param documents 成功加载的文档摘要列表。
     * @param errorMessage 失败时的错误信息，成功时为 null。
     */
    private record DocumentListTaskResult(List<DocumentSummary> documents, String errorMessage) {
        /** 创建成功结果。 */
        private static DocumentListTaskResult success(List<DocumentSummary> documents) {
            return new DocumentListTaskResult(documents, null);
        }

        /** 创建失败结果。 */
        private static DocumentListTaskResult failure(String errorMessage) {
            return new DocumentListTaskResult(List.of(), errorMessage);
        }
    }

    /**
     * 下载、删除、重建索引等后台操作的统一结果。
     *
     * @param title 弹窗标题。
     * @param message 弹窗内容。
     * @param messageType JOptionPane 消息类型。
     * @param refreshDocuments 是否在完成后刷新文档列表。
     */
    private record DocumentOperationTaskResult(
            String title,
            String message,
            int messageType,
            boolean refreshDocuments
    ) {
        /** 创建失败结果。 */
        private static DocumentOperationTaskResult error(String message) {
            return new DocumentOperationTaskResult("Document Operation", message, JOptionPane.ERROR_MESSAGE, false);
        }
    }
}
