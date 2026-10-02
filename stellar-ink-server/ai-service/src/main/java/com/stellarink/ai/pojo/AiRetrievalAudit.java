package com.stellarink.ai.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一次检索的审计记录（表 {@code ai_retrieval_audit}，脚本 {@code deploy/sql/17_ai_retrieval_audit.sql}）。
 *
 * <p><b>刻意不存问题原文</b>（沿用本仓库「审计不记问题原文」的口径）：
 * 存 {@code questionHash} 与 {@code questionChars} 仍然能回答「这问题是不是复现性的」，
 * 而要看原文就按 {@code traceId} 去进程内回放查 —— 那份有内容，但不落库。
 *
 * <p><b>候选数与引用数分开</b>：两者差距大说明「召回了一堆但没一条够格进答案」，
 * 那是提示词或门限的问题；只记一个数会把两种完全不同的情况混成一样。
 */
@Data
@TableName("ai_retrieval_audit")
public class AiRetrievalAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;

    private Long userId;

    /** qa / qa_stream / agent / eval … */
    private String scene;

    private String strategy;

    /** 问题原文的 SHA-256（**不存原文**）。 */
    private String questionHash;

    private Integer questionChars;

    private Integer candidates;

    private Integer citations;

    /** 命中的文章 id（逗号分隔）。 */
    private String postIds;

    private BigDecimal topScore;

    private Boolean refused;

    /** 这次调用是否失败 —— 失败也要留痕，否则「失败率」永远算不出来。 */
    private Boolean failed;

    private Integer latencyMs;

    private String model;

    private LocalDateTime createdAt;
}