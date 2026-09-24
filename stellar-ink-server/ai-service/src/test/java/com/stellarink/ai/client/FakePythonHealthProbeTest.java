package com.stellarink.ai.client;

import com.stellarink.ai.config.AiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M0 的 Fake 下游探活：必须是**诚实**的降级，而不是假装可用。
 */
class FakePythonHealthProbeTest {

    private final AiProperties properties = new AiProperties();

    @Test
    @DisplayName("默认地址是本机回环的 8200（不指向公网，也不注册 Nacos）")
    void defaultBaseUrlIsLoopback() {
        assertEquals("http://127.0.0.1:8200", properties.getPythonBaseUrl());
    }

    @Test
    @DisplayName("M0 探活如实返回不可用，并说明原因指向 M1")
    void reportsUnavailableWithReason() {
        PythonHealthProbe.ProbeResult result = new FakePythonHealthProbe(properties).probe();

        assertFalse(result.available(), "M0 尚未接线，不能假装下游可用");
        assertNotNull(result.reason(), "不可用时必须给出可读原因，否则运维无从下手");
        assertTrue(result.reason().contains("M1"), "原因应指明后续由哪个里程碑补齐");
        // 原因用于日志，不作为公开响应内容（公开响应只给「下游未就绪」这类结论）
        assertTrue(result.reason().contains(properties.getPythonBaseUrl()));
    }
}
