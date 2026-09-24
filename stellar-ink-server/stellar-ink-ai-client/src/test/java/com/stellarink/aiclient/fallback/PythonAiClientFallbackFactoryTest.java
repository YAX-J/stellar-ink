package com.stellarink.aiclient.fallback;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.QaStreamRequestDTO;
import com.stellarink.aiclient.dto.WritingSuggestRequestDTO;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 降级测试：Python 不可用时必须给出**可识别的错误**，而不是空结果。
 *
 * <p>M0 只验证降级语义；真正的熔断/超时配置在 M8（观测与生产保护）。
 */
class PythonAiClientFallbackFactoryTest {

    private final PythonAiClientFallbackFactory factory = new PythonAiClientFallbackFactory();

    private PythonAiClient degradedClient() {
        return factory.create(new ConnectException("Connection refused"));
    }

    private static void assertUnavailable(Runnable action) {
        BusinessException exception = assertThrows(BusinessException.class, action::run);

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE.getCode(), exception.getCode());
        // 文案要能直接展示给用户，且不泄露内网地址或异常堆栈
        assertTrue(exception.getMessage().contains("稍后重试"), exception.getMessage());
    }

    @Test
    @DisplayName("问答降级：抛 503 业务异常")
    void qaStreamDegradesToServiceUnavailable() {
        assertUnavailable(() -> degradedClient().qaStream(new QaStreamRequestDTO()));
    }

    @Test
    @DisplayName("写作建议降级：抛 503 业务异常")
    void writingSuggestDegradesToServiceUnavailable() {
        assertUnavailable(() -> degradedClient().writingSuggest(new WritingSuggestRequestDTO()));
    }

    @Test
    @DisplayName("索引重建与任务查询降级：同样明确报错")
    void indexOperationsDegradeToServiceUnavailable() {
        assertUnavailable(() -> degradedClient().rebuildIndex(null));
        assertUnavailable(() -> degradedClient().indexJob("job-0001"));
    }

    @Test
    @DisplayName("探活失败也要抛出：调用方据此给出「AI 暂不可用」而不是假装健康")
    void healthProbeFailsLoudly() {
        assertUnavailable(() -> degradedClient().health());
    }

    @Test
    @DisplayName("异常原因为空时不抛 NPE（降级路径自身也必须健壮）")
    void handlesNullCause() {
        PythonAiClient client = factory.create(null);

        assertUnavailable(() -> client.indexJob("job-0001"));
    }
}
