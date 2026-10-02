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
 * 抽取结果（M9）：候选 + **按原因分类的丢弃计数**。
 *
 * <p>丢弃计数不是装饰：抽得少时，要能一眼分清「模型没提出」还是「提出了但出处对不上」——
 * 后者的处置是改提示词或换模型，完全不同。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryExtractResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<MemoryCandidateDTO> candidates;

    private MemoryExtractStatsDTO stats;

    private List<String> notes;

    private String usageModel;
}