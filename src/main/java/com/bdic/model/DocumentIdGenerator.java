package com.bdic.model;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 文档编号生成器。
 *
 * <p>客户端上传新文档时使用该工具生成用户可见的文档 ID。生成结果带有 {@code doc-}
 * 前缀，后面拼接 16 字节随机数的十六进制表示，既便于界面识别，也尽量降低碰撞概率。</p>
 */
public final class DocumentIdGenerator {

    /** 全局安全随机数来源，用于产生不可预测的文档编号。 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /** 每个文档编号使用的随机字节数，16 字节会生成 32 个十六进制字符。 */
    private static final int RANDOM_BYTE_LENGTH = 16;

    /** 工具类不需要实例化，私有构造器用于阻止外部 new。 */
    private DocumentIdGenerator() {
    }

    /**
     * 生成一个新的随机文档 ID。
     *
     * @return 形如 {@code doc-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx} 的编号。
     */
    public static String generate() {
        // 先填充固定长度随机字节，再统一编码成小写十六进制字符串。
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return "doc-" + HexFormat.of().formatHex(bytes);
    }
}
