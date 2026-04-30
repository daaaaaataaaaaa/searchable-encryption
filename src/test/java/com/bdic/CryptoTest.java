package com.bdic;

import com.bdic.crypto.DESUtil;
import com.bdic.crypto.PEKSUtil;
import com.bdic.crypto.PasswordUtil;
import junit.framework.TestCase;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

/**
 * 加密相关工具的单元测试。
 */
public class CryptoTest extends TestCase {

    /**
     * 验证 DES 加密后的内容可以用同一密钥正确解密回原文。
     */
    public void testDesRoundTrip() throws Exception {
        // 生成一次性测试密钥和明文。
        SecretKey desKey = DESUtil.generateKey();
        String plaintext = "Hello World Data";

        // 加密后再解密，期望字节内容完整恢复。
        byte[] encrypted = DESUtil.encrypt(plaintext.getBytes(StandardCharsets.UTF_8), desKey);
        byte[] decrypted = DESUtil.decrypt(encrypted, desKey);

        assertEquals(plaintext, new String(decrypted, StandardCharsets.UTF_8));
    }

    /**
     * 验证 PEKS 陷门只匹配同一规范化关键词。
     */
    public void testPeksTrapdoorMatchesOnlySameKeyword() throws Exception {
        KeyPair peksKeyPair = PEKSUtil.generateKeyPair();

        // encrypt 和 getTrapdoor 都会做 trim + lower-case，因此 Secret 与 secret 应该匹配。
        byte[] peksCiphertext = PEKSUtil.encrypt(peksKeyPair.getPublic(), "Secret");
        byte[] trapdoor = PEKSUtil.getTrapdoor(peksKeyPair.getPrivate(), "secret");
        byte[] wrongTrapdoor = PEKSUtil.getTrapdoor(peksKeyPair.getPrivate(), "wrong");

        assertTrue(PEKSUtil.test(peksCiphertext, trapdoor));
        assertFalse(PEKSUtil.test(peksCiphertext, wrongTrapdoor));
    }

    /**
     * 验证 PEKS 公私钥持久化为字节后仍能恢复并完成搜索匹配。
     */
    public void testPeksKeyPairCanBeRestoredFromEncodedBytes() throws Exception {
        KeyPair peksKeyPair = PEKSUtil.generateKeyPair();

        byte[] peksCiphertext = PEKSUtil.encrypt(
                PEKSUtil.getPublicKeyFromBytes(peksKeyPair.getPublic().getEncoded()),
                "restore"
        );
        byte[] trapdoor = PEKSUtil.getTrapdoor(
                PEKSUtil.getPrivateKeyFromBytes(peksKeyPair.getPrivate().getEncoded()),
                "restore"
        );

        assertTrue(PEKSUtil.test(peksCiphertext, trapdoor));
    }

    /**
     * 验证密码摘要校验能接受正确密码并拒绝错误密码。
     */
    public void testPasswordHashVerification() {
        // 注册时保存的是盐和摘要，登录时用同一盐重新计算摘要。
        byte[] salt = PasswordUtil.generateSalt();
        byte[] hash = PasswordUtil.hashPassword("correct horse battery staple", salt);

        assertTrue(PasswordUtil.matches("correct horse battery staple", salt, hash));
        assertFalse(PasswordUtil.matches("wrong password", salt, hash));
    }
}
