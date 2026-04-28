package com.bdic.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.NoSuchAlgorithmException;

/**
 * DES 对称加密工具类。
 *
 * <p>本项目使用它加密文档正文和关键词元数据。DES 主要用于课程/演示场景，
 * 如果用于真实生产系统，建议替换为 AES-GCM 等现代认证加密算法。</p>
 */
public class DESUtil {

    /** JCE 中 DES 算法的标准名称。 */
    private static final String ALGORITHM = "DES";

    /**
     * 生成新的 DES 密钥。
     */
    public static SecretKey generateKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
            // DES 的有效密钥长度固定为 56 位。
            keyGen.init(56);
            return keyGen.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error generating DES key", e);
        }
    }

    /**
     * 根据持久化的原始字节恢复 DES 密钥。
     */
    public static SecretKey getKeyFromBytes(byte[] keyBytes) {
        // SecretKeySpec 不重新派生密钥，只把已保存的原始字节包装成 JCE 可用的密钥对象。
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * 使用指定密钥加密明文字节。
     */
    public static byte[] encrypt(byte[] plaintext, SecretKey key) throws Exception {
        // 使用 JCE Cipher 完成一次性块加密，调用方负责传入完整明文字节。
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(plaintext);
    }

    /**
     * 使用指定密钥解密密文字节。
     */
    public static byte[] decrypt(byte[] ciphertext, SecretKey key) throws Exception {
        // 解密流程与加密流程对称，输出会恢复为上传前的原始字节。
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key);
        return cipher.doFinal(ciphertext);
    }
}
