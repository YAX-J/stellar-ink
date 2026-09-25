package com.stellarink.aiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 写作风格画像（Python {@code WritingStyleProfile} 的 Java 侧对应物）。
 *
 * <p>这是**只读的统计量**：Java 只转发给前端展示、喂给 Copilot 的提示词由 Python 负责，
 * 这一层不做任何加工 —— 一旦在这里「补个字段」或「四舍五入一下」，
 * 前端看到的就不是画像的真实口径了。
 *
 * <p>**字段里没有原句**：`commonPhrases` 只放反复出现（≥3 次）的字组。
 * 这是 Python 侧的硬约束（见 `app/rag/style.py` 的模块说明），Java 不重复实现，
 * 但也**不要**在这里加「顺便带上原文片段」这类便利字段 —— 那等于绕过那条约束。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WritingStyleProfileDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 参与统计的篇数 */
    private Integer sampleCount;

    /** 总字数（中日韩按字 + 拉丁按词） */
    private Integer charCount;

    private Integer paragraphCount;

    private Integer sentenceCount;

    /** 句长中位数（比平均数稳） */
    private Double medianSentenceChars;

    private Integer minSentenceChars;

    private Integer maxSentenceChars;

    /** 短句（≤15 字）占比，0..1 */
    private Double shortSentenceRatio;

    /** 逗号/顿号密度（次/百字） */
    private Double clausesPer100Chars;

    /** 问句占比，0..1 */
    private Double questionRatio;

    /** 破折号/省略号密度，0..1 */
    private Double informalMarkRatio;

    /** 反复出现的字组（≥3 次），**不含原句** */
    private List<String> commonPhrases;

    /** 常用关联词 */
    private List<String> transitions;

    /** 最常用的标签 */
    private List<String> topTags;

    /**
     * 是否存在画像（**不是 JSON 字段**：`@JsonIgnore` 保证它不会漏进契约里）。
     *
     * <p>为什么不加一个 `hasContent` 键：契约的键名与 Python `WritingStyleProfile` 一一对应，
     * 多一个就多一处需要两侧同步的东西；而这个判断纯属调用方的便利方法。
     * 顺带避免了一个真实事故：`@JsonProperty(READ_ONLY)` 只挡反序列化，
     * **序列化时它会以 `content` 为名出现在 JSON 里**，契约测试会以一种莫名其妙的方式红。
     */
    @JsonIgnore
    public boolean hasContent() {
        return sampleCount != null && sampleCount > 0;
    }
}
