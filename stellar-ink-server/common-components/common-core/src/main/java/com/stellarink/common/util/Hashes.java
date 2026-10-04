package com.stellarink.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内容哈希工具（供 RAG 语料快照使用）。
 *
 * <p><b>⚠️ 与 Python 侧的段落哈希不是一回事，别混用</b>：
 *
 * <ul>
 *   <li>{@link #docHash}（这里）：<b>整篇</b>（标题 + 正文）的 SHA-256，由 content-service 算，
 *       用途是「这篇文章有没有变」的廉价判据 —— 对账时先比它，没变就不必重新切块。
 *       索引 payload 里那个 {@code contentHash} 是<b>每个段落子块</b>的哈希（Python 切块后算的），
 *       用途是「索引里的锚点还对得上原文吗」。两者用途不同、算法输入也不同，
 *       同名会让人以为可以互换 —— 所以这里刻意叫 {@code docHash}。</li>
 *   <li>规范化：先统一换行（CRLF/CR → LF）再 {@code strip()}。
 *       否则「同一篇文章因为编辑器换了换行符」就会算出一个新哈希、触发一次不必要的重嵌
 *       （钱不多，但会让「哪些文章变了」这个信号变得不可信）。</li>
 * </ul>
 */
public final class Hashes {

    private Hashes() {
    }

    /** 整篇内容哈希：{@code sha256(title + "\n" + content)}，小写十六进制。 */
    public static String docHash(String title, String content) {
        String normalizedTitle = normalize(title);
        String normalizedContent = normalize(content);
        return sha256Hex(normalizedTitle + "\n" + normalizedContent);
    }

    /** 小写十六进制的 SHA-256。 */
    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte item : hashed) {
                builder.append(Character.forDigit((item >> 4) & 0xF, 16));
                builder.append(Character.forDigit(item & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            // 任何 JVM 都有 SHA-256；真没有就抛，而不是返回一个「看起来像哈希」的垃圾值
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", error);
        }
    }

    /** 统一换行 + 去掉首尾空白（null 视作空串）。 */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }
}
