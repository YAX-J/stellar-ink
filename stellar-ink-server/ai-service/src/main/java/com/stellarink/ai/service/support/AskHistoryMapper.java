package com.stellarink.ai.service.support;

import com.stellarink.aiclient.dto.QaHistoryTurnDTO;
import com.stellarink.sharedmodel.dto.ai.AiHistoryTurnDTO;

import java.util.List;

/**
 * 面向浏览器的历史问答 → Python 内部契约的映射。
 *
 * <p>两个问答出口（{@code /ai/qa} 与 {@code /ai/qa/stream}）共用这一份：各写一遍的结果不是
 * 代码重复，而是**它们会慢慢分叉** —— 比如一处 trim 了问句、另一处没 trim，
 * 于是同一次追问在流式与非流式下拿到的上下文不同。</p>
 *
 * <p>问题与当前问题同口径做 trim（{@code AiQaController}/{@code AiQaStreamController} 都是
 * {@code request.getQuestion().trim()}）；答案**原样**透传 —— 它是我们上一轮发出去的文本，
 * 在这里改写会让「模型看到的自己说过的话」与「用户屏幕上看到的」不一致。</p>
 */
public final class AskHistoryMapper {

    private AskHistoryMapper() {
    }

    /** 映射成内部契约的历史；空 / {@code null} 都返回空列表（Python 侧当「一次性提问」）。 */
    public static List<QaHistoryTurnDTO> toInternal(List<AiHistoryTurnDTO> turns) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        return turns.stream()
                .map(turn -> QaHistoryTurnDTO.builder()
                        .question(turn.getQuestion() == null ? null : turn.getQuestion().trim())
                        .answer(turn.getAnswer())
                        .build())
                .toList();
    }
}
