package com.stellarink.aiclient.dto;

import com.stellarink.aiclient.enums.DoneReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 问答结果（{@code QaAnswer}）。
 *
 * <p>流式场景下 SSE 的 {@code done} 事件携带的关键字段与此等价；
 * {@code evidenceSufficient=false} 时前端必须展示「文章中没有找到依据」，
 * 不允许把拒答包装成正常答案。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QaAnswerDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String answer;

    private List<CitationDTO> citations;

    private DoneReason doneReason;

    private UsageDTO usage;

    private Boolean evidenceSufficient;
}
