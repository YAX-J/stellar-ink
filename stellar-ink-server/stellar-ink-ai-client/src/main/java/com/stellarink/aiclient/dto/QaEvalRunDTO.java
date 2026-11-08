package com.stellarink.aiclient.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** 完整问答评测契约；指标与运行状态由 Python 维护，Java 只转发。 */
@Data
@NoArgsConstructor
public class QaEvalRunDTO {
    private String runId;
    private String dataset;
    private String datasetLabel;
    private Integer total;
    private Integer completed;
    private Boolean busy;
    private List<Case> cases;
    private Summary summary;
    private ModelSummary modelSummary;
    /** 仅本次 step 的用量，避免把整轮累计 Token 重复计账。 */
    private UsageDTO stepUsage;
    private String reportSavedAt;
    private String reportError;
    private Boolean executionAvailable;

    @Data
    @NoArgsConstructor
    public static class StartRequest {
        private String dataset;
        private Integer maxCases;
    }

    @Data
    @NoArgsConstructor
    public static class RunRequest {
        private String runId;
    }

    @Data
    @NoArgsConstructor
    public static class ReviewRequest {
        private String runId;
        private String caseId;
        private String answerHash;
        private Boolean correct;
        private Boolean citationsSupported;
        private String note;
    }

    @Data
    @NoArgsConstructor
    public static class JudgeRequest {
        private String runId;
        private String caseId;
        private String answerHash;
    }

    @Data
    @NoArgsConstructor
    public static class Decision {
        private String choice;
        private Map<String, Double> probabilities;
    }

    @Data
    @NoArgsConstructor
    public static class ModelReview {
        private String model;
        private String answerHash;
        private String rubricVersion;
        private Double threshold;
        private Map<String, Decision> decisions;
        private Boolean correct;
        private Boolean citationsSupported;
        private Boolean needsHumanReview;
        private UsageDTO usage;
    }

    @Data
    @NoArgsConstructor
    public static class ModelSummary {
        private Integer reviewed;
        private Integer conclusive;
        private Integer passed;
        private Double answerPassRate;
        private Integer supportReviewed;
        private Integer supportPassed;
        private Double citationPassRate;
        private Integer needsHuman;
        private Integer totalTokens;
    }

    @Data
    @NoArgsConstructor
    public static class Case {
        private String caseId;
        private String question;
        private List<Long> expectedPostIds;
        private String expectedAnswer;
        private String status;
        private QaAnswerDTO answer;
        private String answerHash;
        private Automatic automatic;
        private Review review;
        private ModelReview modelReview;
        private String error;
        private String traceId;
    }

    @Data
    @NoArgsConstructor
    public static class Automatic {
        private Integer checked;
        private Integer sourceMatched;
        private Integer missingSources;
        private List<Integer> outOfRange;
        private Boolean uncited;
    }

    @Data
    @NoArgsConstructor
    public static class Review {
        private Boolean correct;
        private Boolean citationsSupported;
        private String note;
    }

    @Data
    @NoArgsConstructor
    public static class Summary {
        private Integer errors;
        private Integer totalTokens;
        private Integer returnedCitations;
        private Integer sourceMatched;
        private Double sourceMatchRate;
        private Integer outOfRange;
        private Integer uncitedAnswers;
        private Integer uncitedRefusals;
        private Integer unanswerableCompleted;
        private Integer unanswerableRefused;
        private Integer falseRefusals;
        private Integer truncated;
        private Integer correctReviewed;
        private Integer correctPassed;
        private Double answerAccuracy;
        private Integer supportReviewed;
        private Integer supportPassed;
        private Double citationSupportRate;
    }
}
