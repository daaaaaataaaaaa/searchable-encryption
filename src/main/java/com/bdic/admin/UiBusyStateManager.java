package com.bdic.admin;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一管理客户端繁忙状态：控件禁用、进度条显示、状态文案与等待光标。
 */
public class UiBusyStateManager {

    /** 主窗口，用于切换等待光标。 */
    private final JFrame owner;
    /** 业务执行期间需要禁用的控件集合。 */
    private final List<JComponent> disableWhenBusy = new ArrayList<>();
    /** 上传页状态文本。 */
    private final JLabel uploadStatusLabel;
    /** 上传页进度条。 */
    private final JProgressBar uploadProgressBar;
    /** 搜索页状态文本。 */
    private final JLabel searchStatusLabel;
    /** 搜索页进度条。 */
    private final JProgressBar searchProgressBar;
    /** 文档管理页状态文本。 */
    private final JLabel documentsStatusLabel;
    /** 文档管理页进度条。 */
    private final JProgressBar documentsProgressBar;
    /** 当前是否有后台任务正在运行。 */
    private boolean busy;

    /**
     * 绑定窗口和三个业务页的状态控件。
     */
    public UiBusyStateManager(
            JFrame owner,
            JLabel uploadStatusLabel,
            JProgressBar uploadProgressBar,
            JLabel searchStatusLabel,
            JProgressBar searchProgressBar,
            JLabel documentsStatusLabel,
            JProgressBar documentsProgressBar
    ) {
        this.owner = owner;
        this.uploadStatusLabel = uploadStatusLabel;
        this.uploadProgressBar = uploadProgressBar;
        this.searchStatusLabel = searchStatusLabel;
        this.searchProgressBar = searchProgressBar;
        this.documentsStatusLabel = documentsStatusLabel;
        this.documentsProgressBar = documentsProgressBar;
    }

    /**
     * 注册后台任务执行期间需要统一禁用的控件。
     */
    public void registerBusySensitiveComponents(List<JComponent> components) {
        disableWhenBusy.clear();
        disableWhenBusy.addAll(components);
    }

    /** 返回当前应用是否处于忙碌状态。 */
    public boolean isBusy() {
        return busy;
    }

    /** 切换上传任务忙碌状态，并只显示上传页进度条。 */
    public void setUploadBusy(boolean busy, String statusText) {
        setApplicationBusy(busy);
        toggleProgress(uploadProgressBar, busy);
        toggleProgress(searchProgressBar, false);
        toggleProgress(documentsProgressBar, false);
        setLabelText(uploadStatusLabel, statusText);
        if (!busy) {
            setLabelText(searchStatusLabel, " ");
            setLabelText(documentsStatusLabel, " ");
        }
    }

    /** 切换搜索任务忙碌状态，并只显示搜索页进度条。 */
    public void setSearchBusy(boolean busy, String statusText) {
        setApplicationBusy(busy);
        toggleProgress(uploadProgressBar, false);
        toggleProgress(searchProgressBar, busy);
        toggleProgress(documentsProgressBar, false);
        setLabelText(searchStatusLabel, statusText);
        if (!busy) {
            setLabelText(uploadStatusLabel, " ");
            setLabelText(documentsStatusLabel, " ");
        }
    }

    /** 切换文档管理任务忙碌状态，并只显示文档页进度条。 */
    public void setDocumentsBusy(boolean busy, String statusText) {
        setApplicationBusy(busy);
        toggleProgress(uploadProgressBar, false);
        toggleProgress(searchProgressBar, false);
        toggleProgress(documentsProgressBar, busy);
        setLabelText(documentsStatusLabel, statusText);
        if (!busy) {
            setLabelText(uploadStatusLabel, " ");
            setLabelText(searchStatusLabel, " ");
        }
    }

    /** 更新上传页状态文本。 */
    public void updateUploadStatus(String statusText) {
        setLabelText(uploadStatusLabel, statusText);
    }

    /** 更新搜索页状态文本。 */
    public void updateSearchStatus(String statusText) {
        setLabelText(searchStatusLabel, statusText);
    }

    /** 更新文档管理页状态文本。 */
    public void updateDocumentsStatus(String statusText) {
        setLabelText(documentsStatusLabel, statusText);
    }

    /**
     * 应用级忙碌状态：禁用控件并切换鼠标光标。
     */
    private void setApplicationBusy(boolean busy) {
        this.busy = busy;
        for (JComponent component : disableWhenBusy) {
            if (component != null) {
                component.setEnabled(!busy);
            }
        }
        owner.setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    /**
     * 控制进度条显示和不确定进度动画。
     */
    private void toggleProgress(JProgressBar progressBar, boolean visible) {
        if (progressBar == null) {
            return;
        }
        progressBar.setVisible(visible);
        progressBar.setIndeterminate(visible);
    }

    /**
     * 安全设置状态文本；空文本用单个空格占位，避免布局高度跳动。
     */
    private void setLabelText(JLabel label, String text) {
        if (label == null) {
            return;
        }
        label.setText(text == null || text.isBlank() ? " " : text);
    }
}
