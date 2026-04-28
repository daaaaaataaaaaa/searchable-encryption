package com.bdic;

import com.bdic.crypto.DESUtil;
import com.bdic.crypto.PEKSUtil;
import com.bdic.crypto.PasswordUtil;
import junit.framework.TestCase;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

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
     * 验证 PEKS/HMAC 陷门只匹配同一规范化关键词。
     */
    public void testPeksTrapdoorMatchesOnlySameKeyword() throws Exception {
        SecretKey peksKey = PEKSUtil.generateKey();

        // encrypt 和 getTrapdoor 都会做 trim + lower-case，因此 Secret 与 secret 应该匹配。
        byte[] peksCiphertext = PEKSUtil.encrypt(peksKey, "Secret");
        byte[] trapdoor = PEKSUtil.getTrapdoor(peksKey, "secret");
        byte[] wrongTrapdoor = PEKSUtil.getTrapdoor(peksKey, "wrong");

        assertTrue(PEKSUtil.test(peksCiphertext, trapdoor));
        assertFalse(PEKSUtil.test(peksCiphertext, wrongTrapdoor));
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
