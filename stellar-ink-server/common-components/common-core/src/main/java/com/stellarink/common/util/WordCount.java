package com.stellarink.common.util;

import java.util.regex.Pattern;

/**
 * 字数统计口径（全站唯一实现）。
 *
 * <p>规则：先剥掉 Markdown 的「结构符号」，再统计非空白字符数。
 * <ul>
 *   <li>标题井号、引用符、列表符号与序号、围栏代码块的 ``` 标记都不算字；</li>
 *   <li>强调符号 * _ ~ 与行内代码反引号不算字；</li>
 *   <li>链接只算显示文字，URL 不算字；</li>
 *   <li>中英文一律按「非空白字符」计，代码块里的代码仍然算字（它是正文的一部分）。</li>
 * </ul>
 *
 * <p>注意：前端 <code>stellar-ink-web/src/utils/wordCount.js</code> 必须与本类保持同一套规则
 * （编辑器实时字数与落库字数要对得上）。改这里的正则，就要同步改那边。
 */
public final class WordCount {

    /** 围栏代码块：```lang ... ``` —— 只去围栏行，保留代码本身 */
    private static final Pattern CODE_FENCE = Pattern.compile("^\\s*```.*$", Pattern.MULTILINE);
    /** 行首结构符号：# 标题、> 引用、- * + 列表、1. 1) 有序列表。
     *  有序列表要求「序号 + 分隔符 + 空格」（与 Markdown 标准一致），
     *  否则正文里「2024. 那一年」这类行首数字会被误判成列表序号。 */
    private static final Pattern LEADING_MARKER = Pattern.compile(
            "^\\s{0,3}(?:#{1,6}[ \\t]+|>[ \\t]?|[-*+][ \\t]+|\\d{1,9}[.)][ \\t]+)", Pattern.MULTILINE);
    /** 链接：只保留显示文字，丢掉 URL 与图片语法 */
    private static final Pattern LINK = Pattern.compile("!?\\[([^\\]]*)\\]\\([^)]*\\)");
    /** 行内代码与强调符号 */
    private static final Pattern INLINE_MARK = Pattern.compile("[*`_~]");
    /** 所有空白（含全角空格与换行） */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u3000]+");

    private WordCount() {
    }

    /**
     * 统计正文净字数。
     *
     * @param content Markdown 正文，可为 null
     * @return 非空白字符数；空内容返回 0
     */
    public static int count(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        String plain = content;
        plain = CODE_FENCE.matcher(plain).replaceAll("");
        plain = LEADING_MARKER.matcher(plain).replaceAll("");
        plain = LINK.matcher(plain).replaceAll("$1");
        plain = INLINE_MARK.matcher(plain).replaceAll("");
        plain = WHITESPACE.matcher(plain).replaceAll("");
        return plain.length();
    }
}
