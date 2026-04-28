package com.bdic.admin;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;

/**
 * 统一生成与样式化常用 Swing 组件，减少控制器重复代码。
 */
public final class UiComponentFactory {

    /** 工具类不需要实例化。 */
    private UiComponentFactory() {
    }

    /**
     * 创建带标题、说明和内容区域的统一分组面板。
     */
    public static JPanel createSectionPanel(String title, String description, Component content) {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(createSectionBorder());

        // 标题使用加粗字体，说明使用灰色 HTML 文本以弱化视觉权重。
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 15f));
        JLabel descriptionLabel = new JLabel("<html><span style='color:#6b7280;'>" + description + "</span></html>");

        JPanel header = new JPanel(new BorderLayout(4, 4));
        header.add(titleLabel, BorderLayout.NORTH);
        header.add(descriptionLabel, BorderLayout.CENTER);

        panel.add(header, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    /** 把按钮设置为主操作样式。 */
    public static void stylePrimaryButton(JButton button) {
        button.setBackground(new Color(37, 99, 235));
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
    }

    /** 把按钮设置为次要操作样式。 */
    public static void styleSecondaryButton(JButton button) {
        button.setBackground(new Color(243, 244, 246));
        button.setForeground(new Color(31, 41, 55));
        button.setFocusPainted(false);
    }

    /** 把按钮设置为危险操作样式，例如删除或退出。 */
    public static void styleDangerButton(JButton button) {
        button.setBackground(new Color(220, 38, 38));
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
    }

    /**
     * 创建统一的分组边框：外层浅灰线，内层留白。
     */
    private static Border createSectionBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 224, 230)),
                BorderFactory.createEmptyBorder(12, 14, 12, 14)
        );
    }
}
