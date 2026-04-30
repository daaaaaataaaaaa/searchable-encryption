package com.bdic.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Locale;

/**
 * 可搜索关键词加密工具类。
 *
 * <p>PEKS 的标准思想是：用公钥为关键词生成可搜索密文，用私钥为查询词生成 trapdoor，
 * 服务端只拿密文和 trapdoor 执行匹配测试。本项目不额外引入双线性对库，因此使用 JDK
 * 自带 RSA trapdoor permutation 表达教学级的非对称 PEKS 接口形态；它不是生产级标准 PEKS。</p>
 */
public class PEKSUtil {

    /** 搜索密钥使用的非对称算法。 */
    private static final String KEY_ALGORITHM = "RSA";

    /** RSA 搜索密钥长度。 */
    private static final int KEY_SIZE_BITS = 2048;

    /** 关键词映射到整数代表元时使用的哈希算法。 */
    private static final String HASH_ALGORITHM = "SHA-256";

    /** 二进制载荷格式标识，避免误把旧数据解析成新格式。 */
    private static final byte[] MAGIC = new byte[]{'P', 'E', 'K', 'S'};

    /** 关键词密文载荷类型。 */
    private static final byte CIPHERTEXT_TYPE = 1;

    /** 查询陷门载荷类型。 */
    private static final byte TRAPDOOR_TYPE = 2;

    /** 哈希域分离标签，避免和项目内其他 SHA-256 用途混淆。 */
    private static final byte[] KEYWORD_HASH_DOMAIN = "PEKS-RSA-KEYWORD-v1".getBytes(StandardCharsets.US_ASCII);

    /**
     * 生成 PEKS 搜索公私钥对。公钥用于上传阶段生成关键词密文，私钥用于搜索阶段生成 trapdoor。
     */
    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        keyPairGenerator.initialize(KEY_SIZE_BITS);
        return keyPairGenerator.generateKeyPair();
    }

    /**
     * 根据本地保存的 X.509 字节恢复 PEKS 公钥。
     */
    public static PublicKey getPublicKeyFromBytes(byte[] keyBytes) throws GeneralSecurityException {
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        return keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    /**
     * 根据本地保存的 PKCS#8 字节恢复 PEKS 私钥。
     */
    public static PrivateKey getPrivateKeyFromBytes(byte[] keyBytes) throws GeneralSecurityException {
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    /**
     * 用搜索公钥为关键词生成可搜索密文，随文档一起保存到服务端索引表。
     */
    public static byte[] encrypt(PublicKey publicSearchKey, String keyword) throws Exception {
        RSAPublicKey rsaPublicKey = requireRsaPublicKey(publicSearchKey);
        BigInteger keywordRepresentative = keywordRepresentative(rsaPublicKey.getModulus(), keyword);
        BigInteger encryptedRepresentative = keywordRepresentative.modPow(
                rsaPublicKey.getPublicExponent(),
                rsaPublicKey.getModulus()
        );

        return encodePayload(
                CIPHERTEXT_TYPE,
                rsaPublicKey.getModulus(),
                rsaPublicKey.getPublicExponent(),
                encryptedRepresentative
        );
    }

    /**
     * 用搜索私钥为查询词生成 trapdoor，服务端用它和关键词密文做匹配。
     */
    public static byte[] getTrapdoor(PrivateKey privateSearchKey, String query) throws Exception {
        RSAPrivateKey rsaPrivateKey = requireRsaPrivateKey(privateSearchKey);
        BigInteger keywordRepresentative = keywordRepresentative(rsaPrivateKey.getModulus(), query);
        BigInteger trapdoorValue = keywordRepresentative.modPow(
                rsaPrivateKey.getPrivateExponent(),
                rsaPrivateKey.getModulus()
        );

        return encodePayload(TRAPDOOR_TYPE, rsaPrivateKey.getModulus(), trapdoorValue);
    }

    /**
     * 兼容旧代码入口，内部直接转到正式的 trapdoor 生成方法。
     */
    public static byte[] getInternalTrapdoor(PrivateKey key, String keyword) throws Exception {
        return getTrapdoor(key, keyword);
    }

    /**
     * 服务端测试一个 PEKS 密文是否被当前 trapdoor 命中。
     */
    public static boolean test(byte[] peksCiphertext, byte[] trapdoor) {
        try {
            RsaPeksCiphertext ciphertext = decodeCiphertext(peksCiphertext);
            RsaPeksTrapdoor queryTrapdoor = decodeTrapdoor(trapdoor);
            if (!ciphertext.modulus().equals(queryTrapdoor.modulus())) {
                return false;
            }

            BigInteger recoveredKeywordRepresentative = queryTrapdoor.value().modPow(
                    ciphertext.publicExponent(),
                    ciphertext.modulus()
            );
            BigInteger expectedCiphertext = recoveredKeywordRepresentative.modPow(
                    ciphertext.publicExponent(),
                    ciphertext.modulus()
            );
            int encodedLength = unsignedLength(ciphertext.modulus());
            return MessageDigest.isEqual(
                    toFixedLength(ciphertext.encryptedRepresentative(), encodedLength),
                    toFixedLength(expectedCiphertext, encodedLength)
            );
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 把关键词规范化后映射到 RSA 模数空间中的稳定代表元。
     */
    private static BigInteger keywordRepresentative(BigInteger modulus, String keyword) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
        digest.update(KEYWORD_HASH_DOMAIN);
        digest.update((byte) 0);
        digest.update(normalizeKeyword(keyword).getBytes(StandardCharsets.UTF_8));

        BigInteger representative = new BigInteger(1, digest.digest());
        if (representative.signum() == 0) {
            return BigInteger.ONE;
        }
        if (representative.compareTo(modulus) >= 0) {
            return representative.mod(modulus.subtract(BigInteger.ONE)).add(BigInteger.ONE);
        }
        return representative;
    }

    /**
     * 统一关键词大小写和首尾空白，保证上传和搜索使用同一匹配口径。
     */
    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 编码 PEKS 密文或 trapdoor。每个 BigInteger 都按无符号大端字节写入。
     */
    private static byte[] encodePayload(byte payloadType, BigInteger... values) {
        try {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(byteStream);
            output.write(MAGIC);
            output.writeByte(payloadType);
            output.writeByte(values.length);
            for (BigInteger value : values) {
                byte[] encodedValue = toUnsignedBytes(value);
                output.writeInt(encodedValue.length);
                output.write(encodedValue);
            }
            output.flush();
            return byteStream.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode PEKS payload", e);
        }
    }

    /**
     * 解码并校验 PEKS 关键词密文。
     */
    private static RsaPeksCiphertext decodeCiphertext(byte[] payload) {
        BigInteger[] values = decodePayload(payload, CIPHERTEXT_TYPE, 3);
        return new RsaPeksCiphertext(values[0], values[1], values[2]);
    }

    /**
     * 解码并校验 PEKS 查询 trapdoor。
     */
    private static RsaPeksTrapdoor decodeTrapdoor(byte[] payload) {
        BigInteger[] values = decodePayload(payload, TRAPDOOR_TYPE, 2);
        return new RsaPeksTrapdoor(values[0], values[1]);
    }

    /**
     * 解码通用 PEKS 载荷。
     */
    private static BigInteger[] decodePayload(byte[] payload, byte expectedType, int expectedValueCount) {
        if (payload == null) {
            throw new IllegalArgumentException("PEKS payload is required");
        }

        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            byte[] magic = input.readNBytes(MAGIC.length);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IllegalArgumentException("Unsupported PEKS payload");
            }

            byte payloadType = input.readByte();
            int valueCount = input.readUnsignedByte();
            if (payloadType != expectedType || valueCount != expectedValueCount) {
                throw new IllegalArgumentException("Unexpected PEKS payload type");
            }

            BigInteger[] values = new BigInteger[valueCount];
            for (int i = 0; i < valueCount; i++) {
                int length = input.readInt();
                if (length <= 0 || length > 8192) {
                    throw new IllegalArgumentException("Invalid PEKS integer length");
                }

                byte[] valueBytes = input.readNBytes(length);
                if (valueBytes.length != length) {
                    throw new IllegalArgumentException("Truncated PEKS payload");
                }
                values[i] = new BigInteger(1, valueBytes);
            }

            if (input.available() != 0) {
                throw new IllegalArgumentException("Trailing bytes in PEKS payload");
            }
            return values;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid PEKS payload", e);
        }
    }

    /**
     * 要求调用方传入 RSA 公钥。
     */
    private static RSAPublicKey requireRsaPublicKey(PublicKey publicKey) {
        if (publicKey instanceof RSAPublicKey rsaPublicKey) {
            return rsaPublicKey;
        }
        throw new IllegalArgumentException("PEKS public key must be an RSA public key");
    }

    /**
     * 要求调用方传入 RSA 私钥。
     */
    private static RSAPrivateKey requireRsaPrivateKey(PrivateKey privateKey) {
        if (privateKey instanceof RSAPrivateKey rsaPrivateKey) {
            return rsaPrivateKey;
        }
        throw new IllegalArgumentException("PEKS private key must be an RSA private key");
    }

    /**
     * BigInteger.toByteArray 可能带符号位，这里统一转成无符号表示。
     */
    private static byte[] toUnsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    /**
     * 按固定长度左侧补零，便于常量时间比较。
     */
    private static byte[] toFixedLength(BigInteger value, int length) {
        byte[] unsigned = toUnsignedBytes(value);
        if (unsigned.length == length) {
            return unsigned;
        }

        byte[] fixed = new byte[length];
        int copyLength = Math.min(unsigned.length, length);
        System.arraycopy(unsigned, unsigned.length - copyLength, fixed, length - copyLength, copyLength);
        return fixed;
    }

    /**
     * 返回无符号编码长度。
     */
    private static int unsignedLength(BigInteger value) {
        return toUnsignedBytes(value).length;
    }

    /**
     * RSA PEKS 关键词密文结构。
     */
    private record RsaPeksCiphertext(BigInteger modulus, BigInteger publicExponent, BigInteger encryptedRepresentative) {
    }

    /**
     * RSA PEKS 查询 trapdoor 结构。
     */
    private record RsaPeksTrapdoor(BigInteger modulus, BigInteger value) {
    }
}
