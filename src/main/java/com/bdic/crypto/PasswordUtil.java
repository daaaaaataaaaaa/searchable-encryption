package com.bdic.crypto;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 用户密码处理工具。
 *
 * <p>服务端不保存明文密码，只保存随机盐和 PBKDF2 派生出的密码摘要。</p>
 */
public class PasswordUtil {

    /** PBKDF2 迭代次数，次数越高暴力破解成本越高。 */
    private static final int ITERATIONS = 65536;
    /** 派生出的密码摘要长度，单位为 bit。 */
    private static final int KEY_LENGTH = 256;
    /** 随机盐长度，单位为字节。 */
    private static final int SALT_LENGTH = 16;

    /**
     * 为每个用户生成独立随机盐。
     */
    public static byte[] generateSalt() {
        // 每次注册都生成独立盐，避免相同密码在数据库中出现相同摘要。
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /**
     * 使用 PBKDF2WithHmacSHA256 生成加盐密码摘要。
     */
    public static byte[] hashPassword(String password, byte[] salt) {
        try {
            // PBEKeySpec 接收字符数组密码、盐、迭代次数和输出长度，由工厂完成密钥派生。
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("生成密码摘要失败", e);
        }
    }

    /**
     * 校验用户输入密码是否与数据库中的盐和摘要匹配。
     */
    public static boolean matches(String password, byte[] salt, byte[] expectedHash) {
        // 用同一盐值重新计算摘要，再与数据库保存的摘要比较。
        byte[] actualHash = hashPassword(password, salt);
        return Arrays.equals(actualHash, expectedHash);
    }
}
