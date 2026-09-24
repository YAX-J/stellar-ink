package com.stellarink.aiclient.dto;

import com.stellarink.aiclient.enums.WritingTask;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 写作建议结果（{@code WritingSuggestResult}）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WritingSuggestResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 回显任务类型，便于前端分流渲染 */
    private WritingTask task;

    private List<WritingCandidateDTO> candidates;

    private UsageDTO usage;
}
