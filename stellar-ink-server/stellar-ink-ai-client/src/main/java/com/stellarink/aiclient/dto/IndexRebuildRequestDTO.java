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

    /** {@code post_rebuild} 时的目标文章 ID */
    private Long postId;

    /** 触发原因，写入审计日志便于回溯 */
    private String reason;
}
