package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Python 侧的链路事件（{@code GET /internal/trace/{traceId}}，E3-4）。
 *
 * <p><b>事件字段刻意用 {@code List<Map<String,Object>>} 而不是一棵 DTO 树</b>：
 * 事件的键**按 kind 而不同**（检索是 topK/posts/refused…，模型是 call/model/tokens…，
 * 工具是 tool/label/citations…），而且这份形状归 Python 所有。在 Java 再定义一遍，
 * 等于把 Python 的事件契约定死两处 —— 加一个字段就要改两个语言四处文件，
 * 而漏改的表现是「排障时少了一列，没人发现」。
 *
 * <p>代价是编译期没有字段检查；换来的是「Python 加字段，Java 与前端自动就能看到」。
 * 键名与形状由两侧共读的 fixture（{@code tests/fixtures/trace_replay_response.json}）守住。
 *
 * <p>{@code found=false} 表示**这一台没有这条链路的记录**（Python 侧的缓冲有界、
 * 且是进程内的），不代表「这条链路不存在」—— 不要把 false 当成「伪造的 traceId」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTraceDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String traceId;

    private Boolean found;

    /** 事件列表：每项至少含 {@code atMs} 与 {@code kind} */
    private List<Map<String, Object>> events;
}
