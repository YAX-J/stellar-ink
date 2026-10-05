package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一条核验问题（Python {@code AgentVerifyProblemView}）。
 *
 * <p>{@code kind} 给程序（{@code outOfRange} / {@code uncited} / {@code snippetNotFound} /
 * {@code unknownChunk}），{@code message} 给人 —— 前端直接把 message 显示出来，
 * 不要自己按 kind 再编一句文案：文案在 Python 侧与判定一起改，两处写必然分叉。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentVerifyProblemDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 问题分类；Java 侧不做枚举映射（新增一类问题不该要求同步改 Java） */
    private String kind;

    /** 可读说明（可直接展示给用户） */
    private String message;
}
