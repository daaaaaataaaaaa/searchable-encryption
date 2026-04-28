package com.bdic;

import com.bdic.model.DocumentIdGenerator;
import junit.framework.TestCase;

import java.util.HashSet;
import java.util.Set;

/**
 * 文档 ID 生成器测试。
 */
public class DocumentIdGeneratorTest extends TestCase {

    /**
     * 验证生成的 ID 格式正确，并在样本范围内不重复。
     */
    public void testGeneratedDocumentIdFormatAndUniqueness() {
        Set<String> generatedIds = new HashSet<>();

        // 生成 1000 个样本，检查 doc- 前缀和 32 位小写十六进制随机部分。
        for (int i = 0; i < 1000; i++) {
            String docId = DocumentIdGenerator.generate();

            assertTrue(docId.matches("doc-[0-9a-f]{32}"));
            assertTrue(generatedIds.add(docId));
        }
    }
}
