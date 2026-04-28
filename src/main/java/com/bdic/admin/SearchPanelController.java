package com.bdic.admin;

import com.bdic.crypto.ClientKeyManager;
import com.bdic.crypto.DESUtil;
import com.bdic.crypto.PEKSUtil;
import com.bdic.model.DocumentSummary;
import com.bdic.model.EncryptedData;
import com.bdic.model.ServerResponse;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 搜索页控制器：负责关键词搜索 UI 与异步搜索流程。
 */
public class SearchPanelController {
    /** 搜索结果中图片缩略图最大宽度。 */
    private static final int PREVIEW_THUMB_MAX_WIDTH = 180;
    /** 搜索结果中图片缩略图最大高度。 */
    private static final int PREVIEW_THUMB_MAX_HEIGHT = 130;


    /** 主窗口，用于弹窗定位。 */
    private final JFrame owner;
    /** 与服务端通信的客户端。 */
    private final DocumentServiceClient serviceClient;
    /** 本地解密和文件类型判断服务。 */
    private final DocumentOperationService operationService;
    /** 当前用户本地密钥。 */
    private final ClientKeyManager.KeyBundle keyBundle;
    /** 全局忙碌状态管理器。 */
    private UiBusyStateManager busyStateManager;

    /** 搜索关键词输入框。 */
    private JTextField searchField;
    /** 搜索结果卡片容器。 */
    private JPanel resultListPanel;
    /** 触发搜索按钮。 */
    private JButton searchButton;
    /** 结果滚动区域。 */
    private JScrollPane imagePreviewScrollPane;
    /** 搜索页状态文本。 */
    private JLabel searchStatusLabel;
    /** 搜索页进度条。 */
    private JProgressBar searchProgressBar;

    /**
     * 创建搜索页控制器。
     */
    public SearchPanelController(
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
     * 创建搜索页完整 UI。
     */
    public JPanel createPanel() {
        JPanel searchPanel = new JPanel(new BorderLayout(10, 10));
        searchPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 顶部搜索栏支持按钮搜索，也支持在输入框中按回车搜索。
        JPanel searchFormPanel = new JPanel(new BorderLayout(8, 8));
        searchFormPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        searchFormPanel.add(new JLabel("Search Keyword"), BorderLayout.WEST);
        searchField = new JTextField();
        searchField.addActionListener(e -> handleSearch());
        searchFormPanel.add(searchField, BorderLayout.CENTER);

        searchButton = new JButton("Search");
        searchButton.addActionListener(e -> handleSearch());
        UiComponentFactory.stylePrimaryButton(searchButton);
        searchFormPanel.add(searchButton, BorderLayout.EAST);
        searchPanel.add(UiComponentFactory.createSectionPanel("Keyword Search", "Search by keyword prefix, file name, or extracted document text.", searchFormPanel), BorderLayout.NORTH);

        // 中间结果区按纵向卡片展示，每个卡片可能包含图片缩略图或文本预览。
        resultListPanel = new JPanel();
        resultListPanel.setLayout(new BoxLayout(resultListPanel, BoxLayout.Y_AXIS));
        resultListPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        imagePreviewScrollPane = new JScrollPane(resultListPanel);
        imagePreviewScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        imagePreviewScrollPane.getVerticalScrollBar().setUnitIncrement(18);

        JPanel resultContentPanel = new JPanel(new BorderLayout(8, 8));
        resultContentPanel.add(imagePreviewScrollPane, BorderLayout.CENTER);
        searchPanel.add(UiComponentFactory.createSectionPanel("Search Results", "Matched documents and text previews are shown here.", resultContentPanel), BorderLayout.CENTER);

        searchStatusLabel = new JLabel(" ");
        searchStatusLabel.setForeground(new Color(75, 85, 99));
        searchProgressBar = new JProgressBar();
        searchProgressBar.setIndeterminate(true);
        searchProgressBar.setVisible(false);
        searchProgressBar.setPreferredSize(new Dimension(160, 18));

        JPanel searchStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        searchStatusPanel.add(searchProgressBar);
        searchStatusPanel.add(Box.createHorizontalStrut(8));
        searchStatusPanel.add(searchStatusLabel);
        searchPanel.add(searchStatusPanel, BorderLayout.SOUTH);
        return searchPanel;
    }

    /** 注入忙碌状态管理器。 */
    public void setBusyStateManager(UiBusyStateManager busyStateManager) {
        this.busyStateManager = busyStateManager;
    }

    /** 返回搜索页状态标签。 */
    public JLabel getStatusLabel() {
        return searchStatusLabel;
    }

    /** 返回搜索页进度条。 */
    public JProgressBar getProgressBar() {
        return searchProgressBar;
    }

    /** 返回后台搜索期间需要禁用的控件。 */
    public List<JComponent> getBusySensitiveComponents() {
        List<JComponent> components = new ArrayList<>();
        components.add(searchButton);
        components.add(searchField);
        return components;
    }

    /**
     * 响应搜索动作：生成陷门、调用服务端搜索，并在完成后渲染结果。
     */
    private void handleSearch() {
        if (busyStateManager == null || busyStateManager.isBusy()) {
            return;
        }

        final String keyword = searchField.getText().trim().toLowerCase(Locale.ROOT);
        if (keyword.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "Please enter a keyword.", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }

        busyStateManager.setSearchBusy(true, "Searching for \"" + keyword + "\"...");
        // 搜索和必要的下载预览都在后台线程执行，避免阻塞 Swing 事件线程。
        new SwingWorker<SearchTaskResult, Void>() {
            @Override
            protected SearchTaskResult doInBackground() {
                try {
                    // 关键词先在客户端用搜索密钥变成陷门，服务端只拿陷门做密文匹配。
                    byte[] trapdoor = PEKSUtil.getTrapdoor(keyBundle.peksKey(), keyword);
                    ServerResponse response = serviceClient.search(trapdoor);
                    if (!response.isSuccess()) {
                        return SearchTaskResult.success(Collections.emptyList());
                    }

                    if (response.getData() instanceof List<?> rawResults) {
                        List<EncryptedData> parsedResults = castSearchResults(rawResults);
                        // 除密文关键词外，再用 docId/文件名做兜底模糊匹配，提升可用性。
                        List<EncryptedData> fallbackResults = searchByDocIdOrFileName(keyword);
                        List<EncryptedData> mergedResults = mergeByDocId(parsedResults, fallbackResults);
                        return SearchTaskResult.success(mergedResults);
                    }
                    return SearchTaskResult.success(Collections.emptyList());
                } catch (Exception ex) {
                    ex.printStackTrace();
                    return SearchTaskResult.failure("Search failed: " + DocumentOperationService.describeException(ex));
                }
            }

            @Override
            protected void done() {
                SearchTaskResult result;
                try {
                    result = get();
                } catch (Exception ex) {
                    ex.printStackTrace();
                    result = SearchTaskResult.failure("Search failed: " + DocumentOperationService.describeException(ex));
                }

                if (busyStateManager != null) {
                    busyStateManager.setSearchBusy(false, " ");
                }
                if (result.errorMessage() != null) {
                    JOptionPane.showMessageDialog(owner, result.errorMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                renderSearchResults(result.results());
            }
        }.execute();
    }

    /**
     * 将服务端返回的原始列表转换为 EncryptedData 列表。
     */
    private List<EncryptedData> castSearchResults(List<?> rawResults) {
        List<EncryptedData> results = new ArrayList<>();
        for (Object rawResult : rawResults) {
            results.add((EncryptedData) rawResult);
        }
        return results;
    }

    /**
     * 使用文档列表做 docId/文件名模糊匹配，命中后再下载完整文档用于展示。
     */
    private List<EncryptedData> searchByDocIdOrFileName(String keyword) throws Exception {
        ServerResponse listResponse = serviceClient.listDocuments();
        if (!listResponse.isSuccess() || !(listResponse.getData() instanceof List<?> rawSummaries)) {
            return Collections.emptyList();
        }

        List<EncryptedData> matched = new ArrayList<>();
        String normalizedKeyword = keyword.toLowerCase(Locale.ROOT);
        for (Object rawSummary : rawSummaries) {
            if (!(rawSummary instanceof DocumentSummary summary)) {
                continue;
            }
            String docId = summary.getDocId() == null ? "" : summary.getDocId().toLowerCase(Locale.ROOT);
            String fileName = summary.getFileName() == null ? "" : summary.getFileName().toLowerCase(Locale.ROOT);
            if (!docId.contains(normalizedKeyword) && !fileName.contains(normalizedKeyword)) {
                continue;
            }
            // 摘要不含密文正文，因此命中后需要下载完整文档对象。
            ServerResponse downloadResponse = serviceClient.downloadDocument(summary.getDocId());
            if (downloadResponse.isSuccess() && downloadResponse.getData() instanceof EncryptedData data) {
                matched.add(data);
            }
        }
        return matched;
    }

    /**
     * 合并两组结果，并按 docId 去重。
     */
    private List<EncryptedData> mergeByDocId(List<EncryptedData> primary, List<EncryptedData> secondary) {
        List<EncryptedData> merged = new ArrayList<>(primary);
        for (EncryptedData candidate : secondary) {
            if (candidate == null || candidate.getDocId() == null) {
                continue;
            }
            boolean exists = false;
            for (EncryptedData existing : merged) {
                if (existing != null && candidate.getDocId().equals(existing.getDocId())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                merged.add(candidate);
            }
        }
        return merged;
    }

    /**
     * 确保预览所需的加密正文存在；轻量搜索结果缺正文时再下载完整文档。
     */
    private EncryptedData ensurePreviewContentAvailable(EncryptedData data) throws Exception {
        if (data.getEncryptedContent() != null && data.getEncryptedContent().length > 0) {
            return data;
        }
        ServerResponse response = serviceClient.downloadDocument(data.getDocId());
        if (!response.isSuccess() || !(response.getData() instanceof EncryptedData downloaded)) {
            return data;
        }
        return downloaded;
    }

    /**
     * 判断搜索结果是否适合展示图片预览。
     */
    private boolean isPreviewableImage(EncryptedData data) {
        if (data == null) {
            return false;
        }
        String mimeType = data.getMimeType() == null ? "" : data.getMimeType().toLowerCase(Locale.ROOT);
        String mediaType = data.getMediaType() == null ? "" : data.getMediaType().toLowerCase(Locale.ROOT);
        return mimeType.startsWith("image/") || mediaType.contains("image");
    }

    /**
     * 清空并重新渲染搜索结果列表。
     */
    private void renderSearchResults(List<EncryptedData> results) {
        resultListPanel.removeAll();

        JLabel summaryLabel = new JLabel("Found " + results.size() + " documents.");
        summaryLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        resultListPanel.add(summaryLabel);

        if (results.isEmpty()) {
            resultListPanel.add(new JLabel("No matched documents."));
            resultListPanel.revalidate();
            resultListPanel.repaint();
            return;
        }

        // 每条结果独立卡片展示，卡片间用固定垂直间距隔开。
        for (EncryptedData result : results) {
            resultListPanel.add(buildResultCard(result));
            resultListPanel.add(Box.createVerticalStrut(8));
        }
        resultListPanel.revalidate();
        resultListPanel.repaint();
    }

    /**
     * 构建单个搜索结果卡片。
     */
    private JComponent buildResultCard(EncryptedData data) {
        JPanel card = new JPanel(new BorderLayout(6, 6));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(229, 231, 235)),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));

        JComponent previewComponent = buildImagePreviewComponent(data);
        if (previewComponent != null) {
            // 图片缩略图放在卡片顶部，文本信息放在下方。
            JPanel previewRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            previewRow.setOpaque(false);
            previewRow.add(previewComponent);
            card.add(previewRow, BorderLayout.NORTH);
        }

        JTextArea infoArea = new JTextArea();
        infoArea.setEditable(false);
        infoArea.setOpaque(false);
        infoArea.setLineWrap(true);
        infoArea.setWrapStyleWord(true);
        infoArea.setBorder(null);
        infoArea.setText(buildResultInfoText(data));
        card.add(infoArea, BorderLayout.CENTER);
        return card;
    }

    /**
     * 生成结果卡片中的文本描述，包括元数据、描述和可选正文预览。
     */
    private String buildResultInfoText(EncryptedData result) {
        StringBuilder sb = new StringBuilder();
        sb.append("DocID: ").append(result.getDocId()).append('\n');
        sb.append("File: ").append(result.getFileName()).append('\n');
        sb.append("Type: ").append(result.getMediaType()).append(" / ").append(result.getMimeType()).append('\n');
        sb.append("Size: ").append(result.getFileSize()).append(" bytes\n");
        String description = extractDescription(result);
        if (!description.isBlank()) {
            sb.append("Description: ").append(description).append('\n');
        }

        if (operationService.isTextDocument(result)) {
            if (result.getEncryptedContent() == null) {
                sb.append("Content: Preview unavailable. Use the Download action to inspect the original file.");
                return sb.toString();
            }
            try {
                // 文本预览在客户端本地解密，服务端仍不接触明文内容。
                byte[] decryptedBytes = DESUtil.decrypt(result.getEncryptedContent(), keyBundle.desKey());
                String content = new String(decryptedBytes, StandardCharsets.UTF_8);
                sb.append("Content: ").append(truncate(content, 600));
            } catch (Exception exception) {
                sb.append("Content: Preview failed - ").append(DocumentOperationService.describeException(exception));
            }
        } else {
            sb.append("Content: Binary file restored via the Download action.");
        }
        return sb.toString();
    }

    /**
     * 从加密关键词元数据中恢复用户输入的描述。
     */
    private String extractDescription(EncryptedData data) {
        if (data.getEncryptedKeywordMetadata() == null || data.getEncryptedKeywordMetadata().length == 0) {
            return "";
        }
        try {
            byte[] decryptedMetadata = DESUtil.decrypt(data.getEncryptedKeywordMetadata(), keyBundle.desKey());
            String metadataText = new String(decryptedMetadata, StandardCharsets.UTF_8);
            String prefix = DocumentOperationService.DESCRIPTION_METADATA_PREFIX;
            // 描述行使用固定前缀，后续普通关键词行会被跳过。
            for (String line : metadataText.split("\\R")) {
                String value = line == null ? "" : line.trim();
                if (!value.startsWith(prefix)) {
                    continue;
                }
                String encoded = value.substring(prefix.length()).trim();
                if (encoded.isEmpty()) {
                    return "";
                }
                return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    /**
     * 截断过长文本，避免搜索结果卡片被大文档撑得过高。
     */
    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    /**
     * 为图片搜索结果创建可点击缩略图。
     */
    private JComponent buildImagePreviewComponent(EncryptedData data) {
        if (!isPreviewableImage(data)) {
            return null;
        }
        try {
            EncryptedData previewSource = ensurePreviewContentAvailable(data);
            if (previewSource.getEncryptedContent() == null) {
                return null;
            }
            // 图片预览需要在客户端解密原始图片字节，再交给 ImageIO 解码。
            byte[] decryptedBytes = DESUtil.decrypt(previewSource.getEncryptedContent(), keyBundle.desKey());
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(decryptedBytes));
            if (image == null) {
                return null;
            }
            Image thumbnail = scaleImageToFit(image, PREVIEW_THUMB_MAX_WIDTH, PREVIEW_THUMB_MAX_HEIGHT);
            JLabel iconLabel = new JLabel(new ImageIcon(thumbnail));
            iconLabel.setBorder(BorderFactory.createLineBorder(new Color(209, 213, 219)));
            iconLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            iconLabel.setToolTipText("Click to open full preview");
            iconLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    // 缩略图点击后打开模态大图预览。
                    showImagePreviewDialog(data.getFileName(), image);
                }
            });
            return iconLabel;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 展示图片大图预览对话框。
     */
    private void showImagePreviewDialog(String fileName, BufferedImage image) {
        int maxWidth = Math.max(480, owner.getWidth() - 120);
        int maxHeight = Math.max(360, owner.getHeight() - 180);
        Image scaled = scaleImageToFit(image, maxWidth, maxHeight);

        JLabel imageLabel = new JLabel(new ImageIcon(scaled));
        imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        JScrollPane scrollPane = new JScrollPane(imageLabel);
        scrollPane.setPreferredSize(new Dimension(
                Math.min(maxWidth, scaled.getWidth(null) + 24),
                Math.min(maxHeight, scaled.getHeight(null) + 24)
        ));

        JDialog dialog = new JDialog(owner, "Image Preview - " + fileName, true);
        dialog.setLayout(new BorderLayout());
        dialog.add(scrollPane, BorderLayout.CENTER);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /**
     * 将图片等比缩放到指定最大宽高内。
     */
    private Image scaleImageToFit(BufferedImage image, int maxWidth, int maxHeight) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= maxWidth && height <= maxHeight) {
            return image;
        }
        double scale = Math.min(maxWidth / (double) width, maxHeight / (double) height);
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        return image.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH);
    }

    /**
     * 搜索后台任务结果。
     *
     * @param results 搜索成功时的文档列表。
     * @param errorMessage 搜索失败时的错误信息，成功时为 null。
     */
    private record SearchTaskResult(List<EncryptedData> results, String errorMessage) {
        /** 创建成功结果。 */
        private static SearchTaskResult success(List<EncryptedData> results) {
            return new SearchTaskResult(results, null);
        }

        /** 创建失败结果。 */
        private static SearchTaskResult failure(String errorMessage) {
            return new SearchTaskResult(Collections.emptyList(), errorMessage);
        }
    }
}
