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
    private static final int PREVIEW_THUMB_MAX_WIDTH = 180;
    private static final int PREVIEW_THUMB_MAX_HEIGHT = 130;


    private final JFrame owner;
    private final DocumentServiceClient serviceClient;
    private final DocumentOperationService operationService;
    private final ClientKeyManager.KeyBundle keyBundle;
    private UiBusyStateManager busyStateManager;

    private JTextField searchField;
    private JPanel resultListPanel;
    private JButton searchButton;
    private JScrollPane imagePreviewScrollPane;
    private JLabel searchStatusLabel;
    private JProgressBar searchProgressBar;

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

    public JPanel createPanel() {
        JPanel searchPanel = new JPanel(new BorderLayout(10, 10));
        searchPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

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

    public void setBusyStateManager(UiBusyStateManager busyStateManager) {
        this.busyStateManager = busyStateManager;
    }

    public JLabel getStatusLabel() {
        return searchStatusLabel;
    }

    public JProgressBar getProgressBar() {
        return searchProgressBar;
    }

    public List<JComponent> getBusySensitiveComponents() {
        List<JComponent> components = new ArrayList<>();
        components.add(searchButton);
        components.add(searchField);
        return components;
    }

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
        new SwingWorker<SearchTaskResult, Void>() {
            @Override
            protected SearchTaskResult doInBackground() {
                try {
                    byte[] trapdoor = PEKSUtil.getTrapdoor(keyBundle.peksKey(), keyword);
                    ServerResponse response = serviceClient.search(trapdoor);
                    if (!response.isSuccess()) {
                        return SearchTaskResult.success(Collections.emptyList());
                    }

                    if (response.getData() instanceof List<?> rawResults) {
                        List<EncryptedData> parsedResults = castSearchResults(rawResults);
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

    private List<EncryptedData> castSearchResults(List<?> rawResults) {
        List<EncryptedData> results = new ArrayList<>();
        for (Object rawResult : rawResults) {
            results.add((EncryptedData) rawResult);
        }
        return results;
    }

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
            ServerResponse downloadResponse = serviceClient.downloadDocument(summary.getDocId());
            if (downloadResponse.isSuccess() && downloadResponse.getData() instanceof EncryptedData data) {
                matched.add(data);
            }
        }
        return matched;
    }

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

    private boolean isPreviewableImage(EncryptedData data) {
        if (data == null) {
            return false;
        }
        String mimeType = data.getMimeType() == null ? "" : data.getMimeType().toLowerCase(Locale.ROOT);
        String mediaType = data.getMediaType() == null ? "" : data.getMediaType().toLowerCase(Locale.ROOT);
        return mimeType.startsWith("image/") || mediaType.contains("image");
    }

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

        for (EncryptedData result : results) {
            resultListPanel.add(buildResultCard(result));
            resultListPanel.add(Box.createVerticalStrut(8));
        }
        resultListPanel.revalidate();
        resultListPanel.repaint();
    }

    private JComponent buildResultCard(EncryptedData data) {
        JPanel card = new JPanel(new BorderLayout(6, 6));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(229, 231, 235)),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));

        JComponent previewComponent = buildImagePreviewComponent(data);
        if (previewComponent != null) {
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

    private String extractDescription(EncryptedData data) {
        if (data.getEncryptedKeywordMetadata() == null || data.getEncryptedKeywordMetadata().length == 0) {
            return "";
        }
        try {
            byte[] decryptedMetadata = DESUtil.decrypt(data.getEncryptedKeywordMetadata(), keyBundle.desKey());
            String metadataText = new String(decryptedMetadata, StandardCharsets.UTF_8);
            String prefix = DocumentOperationService.DESCRIPTION_METADATA_PREFIX;
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

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private JComponent buildImagePreviewComponent(EncryptedData data) {
        if (!isPreviewableImage(data)) {
            return null;
        }
        try {
            EncryptedData previewSource = ensurePreviewContentAvailable(data);
            if (previewSource.getEncryptedContent() == null) {
                return null;
            }
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
                    showImagePreviewDialog(data.getFileName(), image);
                }
            });
            return iconLabel;
        } catch (Exception ignored) {
            return null;
        }
    }

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

    private record SearchTaskResult(List<EncryptedData> results, String errorMessage) {
        private static SearchTaskResult success(List<EncryptedData> results) {
            return new SearchTaskResult(results, null);
        }

        private static SearchTaskResult failure(String errorMessage) {
            return new SearchTaskResult(Collections.emptyList(), errorMessage);
        }
    }
}
