package com.bdic.text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键词抽取工具。
 *
 * <p>用于把用户描述、文档正文和文件名统一拆成可搜索 token。返回结果保持插入顺序并去重，
 * 这样既能减少索引数量，也能让测试结果稳定。</p>
 */
public final class KeywordExtractor {

    /** 匹配连续字母或数字，包括中文、日文、韩文等 Unicode 字符。 */
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
    /** 匹配 JSON 中 keyword/keywords/Searchable_Keywords 这类字段里的数组值。 */
    private static final Pattern JSON_KEYWORD_ARRAY_PATTERN = Pattern.compile(
            "\"(?i:(?:searchable_)?keywords?|keyword)\"\\s*:\\s*\\[(.*?)]",
            Pattern.DOTALL
    );
    /** 匹配 JSON 中 keyword/keywords/Searchable_Keywords 这类字段里的字符串值。 */
    private static final Pattern JSON_KEYWORD_STRING_PATTERN = Pattern.compile(
            "\"(?i:(?:searchable_)?keywords?|keyword)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
    );
    /** 匹配 JSON 数组中的字符串元素。 */
    private static final Pattern JSON_STRING_ITEM_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
    /** 过滤过短 token，避免大量无意义的一字词进入索引。 */
    private static final int MIN_TOKEN_LENGTH = 2;

    /** 工具类不需要实例化。 */
    private KeywordExtractor() {
    }

    /**
     * 从逗号分隔的输入中抽取关键词，常用于用户手动输入关键词的场景。
     */
    public static List<String> extractCommaSeparated(String input) {
        Set<String> keywords = new LinkedHashSet<>();
        if (input == null || input.isBlank()) {
            return new ArrayList<>();
        }

        // 逐段清洗、转小写、去重，空项和过短项会被 addKeyword 过滤。
        for (String rawKeyword : input.split(",")) {
            addKeyword(keywords, rawKeyword);
        }
        return new ArrayList<>(keywords);
    }

    /**
     * 从自然文本中抽取词语，并为中日韩文本额外生成相邻双字片段。
     */
    public static List<String> extractWords(String text) {
        Set<String> keywords = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }

        Matcher matcher = WORD_PATTERN.matcher(text);
        while (matcher.find()) {
            // 英文等空格分词语言直接使用正则匹配到的词；CJK 再补充 bigram。
            String word = normalize(matcher.group());
            if (!addKeyword(keywords, word) || !containsCjk(word)) {
                continue;
            }
            addCjkBigrams(keywords, word);
        }
        return new ArrayList<>(keywords);
    }

    /**
     * 从文件名中抽取关键词，包括基础文件名、扩展名和拆分后的片段。
     */
    public static List<String> extractFileNameKeywords(String fileName) {
        Set<String> keywords = new LinkedHashSet<>();
        if (fileName == null || fileName.isBlank()) {
            return new ArrayList<>();
        }

        String normalizedName = fileName.trim();
        int extensionSeparator = normalizedName.lastIndexOf('.');
        String baseName = extensionSeparator > 0 ? normalizedName.substring(0, extensionSeparator) : normalizedName;
        String extension = extensionSeparator > 0 && extensionSeparator < normalizedName.length() - 1
                ? normalizedName.substring(extensionSeparator + 1)
                : "";

        // 先按常见分隔符拆基础名，再补充完整基础名和扩展名。
        keywords.addAll(extractWords(baseName.replaceAll("[_\\-\\.\\(\\)\\[\\]\\{\\}]+", " ")));
        addKeyword(keywords, baseName);
        addKeyword(keywords, extension);
        return new ArrayList<>(keywords);
    }

    /**
     * 从 JSON 文本中定向提取关键词字段，支持 keyword/keywords/Searchable_Keywords。
     */
    public static List<String> extractJsonKeywordFields(String jsonText) {
        Set<String> keywords = new LinkedHashSet<>();
        if (jsonText == null || jsonText.isBlank()) {
            return new ArrayList<>();
        }

        Matcher arrayMatcher = JSON_KEYWORD_ARRAY_PATTERN.matcher(jsonText);
        while (arrayMatcher.find()) {
            Matcher itemMatcher = JSON_STRING_ITEM_PATTERN.matcher(arrayMatcher.group(1));
            while (itemMatcher.find()) {
                addKeyword(keywords, decodeJsonString(itemMatcher.group(1)));
            }
        }

        Matcher stringMatcher = JSON_KEYWORD_STRING_PATTERN.matcher(jsonText);
        while (stringMatcher.find()) {
            keywords.addAll(extractCommaSeparated(decodeJsonString(stringMatcher.group(1))));
        }
        return new ArrayList<>(keywords);
    }

    /**
     * 规范化并加入集合；返回值表示该关键词是否足够长且被接受。
     */
    private static boolean addKeyword(Set<String> keywords, String rawKeyword) {
        String keyword = normalize(rawKeyword);
        if (keyword.length() < MIN_TOKEN_LENGTH) {
            return false;
        }
        keywords.add(keyword);
        return true;
    }

    /**
     * 去除首尾空白并转小写，保证上传索引和搜索输入使用同一形式。
     */
    private static String normalize(String rawKeyword) {
        if (rawKeyword == null) {
            return "";
        }
        return rawKeyword.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 处理 JSON 字符串里的常见转义序列，保证关键词可读且可搜索。
     */
    private static String decodeJsonString(String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) {
            return "";
        }

        StringBuilder decoded = new StringBuilder(rawValue.length());
        for (int i = 0; i < rawValue.length(); i++) {
            char current = rawValue.charAt(i);
            if (current != '\\' || i + 1 >= rawValue.length()) {
                decoded.append(current);
                continue;
            }

            char next = rawValue.charAt(++i);
            switch (next) {
                case '"':
                case '\\':
                case '/':
                    decoded.append(next);
                    break;
                case 'b':
                    decoded.append('\b');
                    break;
                case 'f':
                    decoded.append('\f');
                    break;
                case 'n':
                    decoded.append('\n');
                    break;
                case 'r':
                    decoded.append('\r');
                    break;
                case 't':
                    decoded.append('\t');
                    break;
                case 'u':
                    if (i + 4 < rawValue.length()) {
                        String hex = rawValue.substring(i + 1, i + 5);
                        try {
                            decoded.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                            break;
                        } catch (NumberFormatException ignored) {
                            // 回退到原样保留，避免异常中断抽取流程。
                        }
                    }
                    decoded.append("\\u");
                    break;
                default:
                    decoded.append(next);
                    break;
            }
        }
        return decoded.toString();
    }

    /**
     * 判断词语中是否包含中日韩字符。
     */
    private static boolean containsCjk(String word) {
        return word.codePoints().anyMatch(KeywordExtractor::isCjkCodePoint);
    }

    /**
     * 根据 Unicode Script 判断单个码点是否属于 CJK 范围。
     */
    private static boolean isCjkCodePoint(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    /**
     * 为 CJK 文本生成相邻两个字符组成的片段，支持用户用局部词搜索长字符串。
     */
    private static void addCjkBigrams(Set<String> keywords, String word) {
        int[] codePoints = word.codePoints().toArray();
        if (codePoints.length <= MIN_TOKEN_LENGTH) {
            return;
        }

        // 使用 code point 而不是 char，避免代理对字符被错误拆分。
        for (int i = 0; i < codePoints.length - 1; i++) {
            String bigram = new String(codePoints, i, 2);
            addKeyword(keywords, bigram);
        }
    }
}
