package com.stellarink.sharedmodel.vo.ai;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 记忆抽取结果（M9）：候选 + **按原因分类的丢弃计数**。
 *
 * <p>丢弃计数与候选同等重要：抽得少时，它是区分「模型没提出」与
 * 「提出了但出处对不上」的唯一线索 —— 后者的处置是改提示词或换模型，完全不同。
 */
@Data
@Builder
public class AiMemoryExtractVO {

    /** 这次抽出来的候选（已落库为 pending，等人确认）。 */
    private List<AiMemoryVO> candidates;

    /** 模型提出的条数（含被丢弃的）。 */
    private Integer proposed;

    /** 通过校验并落成 pending 的条数。 */
    private Integer kept;

    /** 丢弃原因 → 条数（noEvidence / sensitive / badType / tooShort / tooLong …）。 */
    private Map<String, Integer> dropped;

    private List<String> notes;

    private String usageModel;
}