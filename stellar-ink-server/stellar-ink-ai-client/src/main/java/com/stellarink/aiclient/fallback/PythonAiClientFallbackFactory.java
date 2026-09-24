package com.stellarink.aiclient.fallback;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.IndexJobDTO;
import com.stellarink.aiclient.dto.IndexRebuildRequestDTO;
import com.stellarink.aiclient.dto.QaStreamRequestDTO;
import com.stellarink.aiclient.dto.WritingSuggestRequestDTO;
import com.stellarink.aiclient.dto.WritingSuggestResultDTO;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Python 服务不可用时的降级：**明确报错，不静默成功**（红线 §7.5）。
 *
 * <p>为什么不用「返回空答案」：AI 不可用时前端必须能区分「没有答案」与「服务坏了」，
 * 前者显示拒答文案，后者提示稍后重试或退化为普通搜索。返回空对象会让用户以为文章里真的没有依据。
 *
 * <p>日志只记异常类型与消息，不打印请求体（可能含草稿）。
 */
@Slf4j
@Component
public class PythonAiClientFallbackFactory implements FallbackFactory<PythonAiClient> {

    private static final String UNAVAILABLE_MSG = "AI 服务暂不可用，请稍后重试。";

    @Override
    public PythonAiClient create(Throwable cause) {
        return new PythonAiClient() {

            @Override
            public Map<String, Object> health() {
                // 探活失败要抛出去：调用方（ai-service 的 /ai/health）需要区分
                // 「Python 正常但模型没配」与「Python 根本连不上」，才能给对用户可读的降级结论
                log.warn("AI health 探测失败：{}", describe(cause));
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE_MSG);
            }

            @Override
            public Object qaStream(QaStreamRequestDTO request) {
                log.warn("AI 流式问答降级：{}", describe(cause));
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE_MSG);
            }

            @Override
            public WritingSuggestResultDTO writingSuggest(WritingSuggestRequestDTO request) {
                log.warn("AI 写作建议降级：{}", describe(cause));
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE_MSG);
            }

            @Override
            public IndexJobDTO rebuildIndex(IndexRebuildRequestDTO request) {
                log.warn("AI 索引重建降级：{}", describe(cause));
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE_MSG);
            }

            @Override
            public IndexJobDTO indexJob(String jobId) {
                log.warn("AI 索引任务查询降级：jobId={}", jobId);
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE_MSG);
            }
        };
    }

    private static String describe(Throwable cause) {
        return cause == null ? "unknown" : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
