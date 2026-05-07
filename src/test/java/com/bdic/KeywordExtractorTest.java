package com.bdic;

import com.bdic.text.KeywordExtractor;
import junit.framework.TestCase;

import java.util.Arrays;
import java.util.List;

/**
 * 关键词抽取工具测试。
 */
public class KeywordExtractorTest extends TestCase {

    /**
     * 验证英文自然文本按词抽取且保持原始顺序。
     */
    public void testExtractWordsFromEnglishText() {
        List<String> keywords = KeywordExtractor.extractWords("searchable encryption protects data privacy");

        assertEquals(Arrays.asList("searchable", "encryption", "protects", "data", "privacy"), keywords);
    }

    /**
     * 验证逗号分隔关键词会统一小写、去空白、去重，并过滤一字词。
     */
    public void testCommaSeparatedKeywordsAreNormalizedAndDeduplicated() {
        List<String> keywords = KeywordExtractor.extractCommaSeparated(" Alpha, beta , ALPHA,, a ");

        assertEquals(Arrays.asList("alpha", "beta"), keywords);
    }

    /**
     * 验证中日韩文本会额外生成相邻双字片段，支持局部词搜索。
     */
    public void testExtractWordsAddsCjkBigrams() {
        List<String> keywords = KeywordExtractor.extractWords("\u53ef\u641c\u7d22\u52a0\u5bc6");

        assertTrue(keywords.contains("\u53ef\u641c\u7d22\u52a0\u5bc6"));
        assertTrue(keywords.contains("\u641c\u7d22"));
        assertTrue(keywords.contains("\u52a0\u5bc6"));
    }

    /**
     * 验证可从 JSON 的 Searchable_Keywords 数组中提取完整关键词值。
     */
    public void testExtractJsonKeywordFieldsFromArrayValues() {
        String json = "[{\"Searchable_Keywords\":[\"PROTOCOL:udp\",\"SERVICE:-\",\"STATE:INT\"]}]";

        List<String> keywords = KeywordExtractor.extractJsonKeywordFields(json);

        assertTrue(keywords.contains("protocol:udp"));
        assertTrue(keywords.contains("service:-"));
        assertTrue(keywords.contains("state:int"));
    }

    /**
     * 验证 JSON 的 keyword 字符串支持逗号分隔并自动规范化。
     */
    public void testExtractJsonKeywordFieldsFromStringValue() {
        String json = "{\"keyword\":\" Alert , Malware,alert \"}";

        List<String> keywords = KeywordExtractor.extractJsonKeywordFields(json);

        assertEquals(Arrays.asList("alert", "malware"), keywords);
    }
}
