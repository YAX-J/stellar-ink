package com.stellarink.aiclient.dto;

import com.stellarink.aiclient.enums.IndexJobStatus;
import com.stellarink.aiclient.enums.IndexTaskKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/** 索引任务状态（{@code IndexJob}）：{@code GET /ai/admin/jobs/{id}} 的返回。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexJobDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String jobId;

    private IndexTaskKind kind;

    private IndexJobStatus status;

    private Integer totalPosts;

    private Integer processedPosts;

    private Integer failedPosts;

    /** 失败或部分失败时的中文说明 */
    private String message;

    // Python 契约输出带时区偏移的 ISO 8601（如 2026-09-24T21:00:00+08:00），
    // 用 Instant 接收不需要额外的 JSR-310 模块注册，也不会因时区设置丢偏移
    private Instant createdAt;

    private Instant finishedAt;
}
