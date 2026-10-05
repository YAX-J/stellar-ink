package com.stellarink.aiclient.dto;

import com.stellarink.aiclient.enums.IndexTaskKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/** 索引重建请求（{@code IndexRebuildRequest}）：ADMIN 触发，需写审计日志。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexRebuildRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private IndexTaskKind kind;

    /** {@code post_rebuild} 时的目标文档 ID（配合 {@link #contentKind} 定位） */
    private Long postId;

    /**
     * {@code post_rebuild} 时的内容种类：{@code post}（文章，默认）或 {@code note}（技术笔记）。
     *
     * <p>文档标识是 {@code contentKind + postId} —— 文章 3 与笔记 3 是两个文档，
     * 只给数字 id 会一次命中两篇。全量重建忽略本字段。</p>
     */
    private String contentKind;

    /** 触发原因，写入审计日志便于回溯 */
    private String reason;
}
