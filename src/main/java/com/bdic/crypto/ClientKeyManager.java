package com.bdic.crypto;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Properties;

/**
 * 客户端密钥管理器。
 *
 * <p>每个用户在本机拥有稳定的 DES 密钥和 PEKS 搜索公私钥。这样客户端重启后，
 * 仍然可以解密旧文档并生成能匹配当前 PEKS 索引的陷门。</p>
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
            KeyPair peksKeyPair = PEKSUtil.generateKeyPair();
            save(keyFile, desKey, peksKeyPair.getPublic(), peksKeyPair.getPrivate());
            return new KeyBundle(desKey, peksKeyPair.getPublic(), peksKeyPair.getPrivate());
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
     * 从 properties 文件恢复 DES 密钥和 PEKS 搜索公私钥。
     */
    private KeyBundle load(Path keyFile) throws Exception {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(keyFile)) {
            properties.load(inputStream);
        }

        // properties 中保存的是 Base64 文本，加载后恢复为密钥对象。
        byte[] desBytes = Base64.getDecoder().decode(properties.getProperty("desKey"));
        SecretKey desKey = DESUtil.getKeyFromBytes(desBytes);
        String publicKeyValue = properties.getProperty("peksPublicKey");
        String privateKeyValue = properties.getProperty("peksPrivateKey");
        if (publicKeyValue == null || privateKeyValue == null) {
            // 旧版本只保存 HMAC 搜索密钥，无法拆分出 PEKS 公私钥；这里生成新密钥对并覆盖本地密钥文件。
            KeyPair peksKeyPair = PEKSUtil.generateKeyPair();
            save(keyFile, desKey, peksKeyPair.getPublic(), peksKeyPair.getPrivate());
            return new KeyBundle(desKey, peksKeyPair.getPublic(), peksKeyPair.getPrivate());
        }

        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyValue);
            byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyValue);
            return new KeyBundle(
                    desKey,
                    PEKSUtil.getPublicKeyFromBytes(publicKeyBytes),
                    PEKSUtil.getPrivateKeyFromBytes(privateKeyBytes)
            );
        } catch (IllegalArgumentException | GeneralSecurityException incompatibleSearchKey) {
            // 可能来自曾经的 JPBC 实验版搜索密钥；保留 DES 密钥，换回当前 RSA PEKS 搜索密钥。
            KeyPair peksKeyPair = PEKSUtil.generateKeyPair();
            save(keyFile, desKey, peksKeyPair.getPublic(), peksKeyPair.getPrivate());
            return new KeyBundle(desKey, peksKeyPair.getPublic(), peksKeyPair.getPrivate());
        }
    }

    /**
     * 将密钥以 Base64 形式保存到本地 properties 文件。
     */
    private void save(Path keyFile, SecretKey desKey, PublicKey peksPublicKey, PrivateKey peksPrivateKey) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("desKey", Base64.getEncoder().encodeToString(desKey.getEncoded()));
        properties.setProperty("peksPublicKey", Base64.getEncoder().encodeToString(peksPublicKey.getEncoded()));
        properties.setProperty("peksPrivateKey", Base64.getEncoder().encodeToString(peksPrivateKey.getEncoded()));

        // 使用 properties 格式便于人工检查，同时避免直接写二进制内容。
        try (OutputStream outputStream = Files.newOutputStream(keyFile)) {
            properties.store(outputStream, null);
        }
    }

    /**
     * 当前用户的一组客户端密钥。
     *
     * @param desKey 用于加密和解密文档正文、关键词元数据的 DES 密钥。
     * @param peksPublicKey 用于生成关键词 PEKS 密文的搜索公钥。
     * @param peksPrivateKey 用于生成搜索 trapdoor 的搜索私钥。
     */
    public record KeyBundle(SecretKey desKey, PublicKey peksPublicKey, PrivateKey peksPrivateKey) {
    }
}
