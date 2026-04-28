package com.bdic.crypto;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Properties;

/**
 * 客户端密钥管理器。
 *
 * <p>每个用户在本机拥有稳定的 DES 密钥和搜索密钥。这样客户端重启后，
 * 仍然可以解密旧文档并生成能匹配旧索引的陷门。</p>
 */
public class ClientKeyManager {

    /** 本地密钥文件所在目录，默认位于用户主目录下的隐藏文件夹。 */
    private final Path keyDirectory;

    /**
     * 使用默认客户端密钥目录构造管理器。
     *
     * <p>默认路径放在用户主目录，避免打包或切换工作目录后找不到历史密钥。</p>
     */
    public ClientKeyManager() {
        this(Paths.get(System.getProperty("user.home"), ".searchable-encryption", "client-keys"));
    }

    /**
     * 使用指定目录构造管理器，主要便于测试或定制客户端密钥保存位置。
     */
    public ClientKeyManager(Path keyDirectory) {
        this.keyDirectory = keyDirectory;
    }

    /**
     * 加载指定用户的本地密钥；不存在时自动生成并保存。
     */
    public KeyBundle loadOrCreate(String username) {
        try {
            // 先保证目录存在，再把旧项目目录中的密钥迁移进来。
            Files.createDirectories(keyDirectory);
            Path keyFile = keyDirectory.resolve(username + ".properties");
            migrateLegacyKeyFile(username, keyFile);
            if (Files.exists(keyFile)) {
                return load(keyFile);
            }

            // 首次登录的用户会生成一组新密钥，并写入本地文件供后续会话复用。
            SecretKey desKey = DESUtil.generateKey();
            SecretKey peksKey = PEKSUtil.generateKey();
            save(keyFile, desKey, peksKey);
            return new KeyBundle(desKey, peksKey);
        } catch (Exception e) {
            throw new RuntimeException("加载客户端密钥失败", e);
        }
    }

    /**
     * 兼容早期项目目录下的 client-keys 文件夹，自动迁移到用户目录。
     */
    private void migrateLegacyKeyFile(String username, Path keyFile) throws IOException {
        if (Files.exists(keyFile)) {
            return;
        }

        // 旧版本把密钥放在项目目录 client-keys 下；发现后复制到新位置。
        Path legacyDirectory = Paths.get("client-keys");
        Path legacyKeyFile = legacyDirectory.resolve(username + ".properties");
        if (!Files.exists(legacyKeyFile)) {
            return;
        }

        Files.createDirectories(keyDirectory);
        Files.copy(legacyKeyFile, keyFile);
    }

    /**
     * 从 properties 文件恢复 DES 密钥和搜索密钥。
     */
    private KeyBundle load(Path keyFile) throws IOException {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(keyFile)) {
            properties.load(inputStream);
        }

        // properties 中保存的是 Base64 文本，加载后恢复为 SecretKey 对象。
        byte[] desBytes = Base64.getDecoder().decode(properties.getProperty("desKey"));
        byte[] peksBytes = Base64.getDecoder().decode(properties.getProperty("peksKey"));
        return new KeyBundle(DESUtil.getKeyFromBytes(desBytes), PEKSUtil.getKeyFromBytes(peksBytes));
    }

    /**
     * 将密钥以 Base64 形式保存到本地 properties 文件。
     */
    private void save(Path keyFile, SecretKey desKey, SecretKey peksKey) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("desKey", Base64.getEncoder().encodeToString(desKey.getEncoded()));
        properties.setProperty("peksKey", Base64.getEncoder().encodeToString(peksKey.getEncoded()));

        // 使用 properties 格式便于人工检查，同时避免直接写二进制内容。
        try (OutputStream outputStream = Files.newOutputStream(keyFile)) {
            properties.store(outputStream, null);
        }
    }

    /**
     * 当前用户的一组客户端密钥。
     *
     * @param desKey 用于加密和解密文档正文、关键词元数据的 DES 密钥。
     * @param peksKey 用于生成关键词密文和搜索陷门的搜索密钥。
     */
    public record KeyBundle(SecretKey desKey, SecretKey peksKey) {
    }
}
