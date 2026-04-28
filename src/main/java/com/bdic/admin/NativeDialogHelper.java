package com.bdic.admin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Windows 原生对话框工具，负责文件夹选择等系统级交互。
 */
public final class NativeDialogHelper {

    /** 工具类不需要实例化。 */
    private NativeDialogHelper() {
    }

    /**
     * 调用 Windows 原生文件夹选择框，并返回用户选择的文件夹路径。
     *
     * <p>Swing 的 {@link javax.swing.JFileChooser} 在部分 Windows 环境下文件夹体验较弱，
     * 因此这里通过 PowerShell 调用 FolderBrowserDialog。</p>
     */
    public static String chooseFolder(String description) {
        // PowerShell 输出路径前先做 Base64 编码，避免中文路径或特殊字符在进程输出中损坏。
        String script = "$dialog = New-Object System.Windows.Forms.FolderBrowserDialog; "
                + "$dialog.Description = '" + escapePowerShellSingleQuoted(description) + "'; "
                + "$dialog.ShowNewFolderButton = $false; "
                + "if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { "
                + "Write-Output ([Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($dialog.SelectedPath))) }";
        ProcessBuilder processBuilder = new ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-STA",
                "-Command",
                "Add-Type -AssemblyName System.Windows.Forms; " + script
        );
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            String output;
            try (var inputStream = process.getInputStream()) {
                output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            int exitCode = process.waitFor();
            if (exitCode != 0 || output.isBlank()) {
                return null;
            }
            // 取最后一行非空输出，规避 PowerShell 可能输出额外提示文本。
            String encodedPath = output.lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (encodedPath == null || encodedPath.isBlank()) {
                return null;
            }
            return new String(Base64.getDecoder().decode(encodedPath), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 转义单引号，保证描述文本可以安全放进 PowerShell 单引号字符串。
     */
    private static String escapePowerShellSingleQuoted(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
