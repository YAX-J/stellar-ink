package com.stellarink.ai.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一条 AI 调用账（表 {@code ai_call_log}，见 {@code deploy/sql/12_ai_call_log.sql}）。
 *
 * <p>为什么不复用日志：日志会滚动、无法聚合、也不含成本。「这个月花了多少钱」「谁把配额用完了」
 * 这类问题必须能按用户 / 场景 / 模型聚合着查，而不是 grep 一堆滚掉一半的文本。
 *
 * <p><b>token 为空 ≠ 0</b>：上游没回报用量时三个 token 列都是 {@code NULL}。
 * 写成 0 会让「没计量」在成本看板上表现成「这次免费」——那是看起来最正常的一种假数据。
 *
 * <p><b>单价是快照</b>：记账时从角色配置读当时的单价写进来，事后改单价不改写历史账目。
 */
@Data
@TableName("ai_call_log")
public class AiCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 链路 traceId：与响应头 {@code X-Trace-Id} 同一个，用来把网关→Java→Python 串起来 */
    private String traceId;

    private Long userId;

    /** 调用者角色：READER/AUTHOR/ADMIN */
    private String role;

    /** 调用场景：qa/qa_stream/writing_suggest/agent/eval */
    private String scene;

    /** 用到的模型角色：chat/embedding/rerank；评测一次用多个模型，故为空 */
    private String providerRole;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Integer latencyMs;

    /** 1 成功 / 0 失败 —— 失败也要记，否则失败率无从统计 */
    private Integer success;

    /** 失败分类（异常类名，不含报文） */
    private String errorCode;

    private BigDecimal priceInput;

    private BigDecimal priceOutput;

    private LocalDateTime createdAt;
}
