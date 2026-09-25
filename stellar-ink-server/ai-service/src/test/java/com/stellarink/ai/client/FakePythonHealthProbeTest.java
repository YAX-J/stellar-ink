package com.stellarink.ai.client;

import com.stellarink.ai.config.AiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 假探针：测试/演示专用，必须是**诚实**的「不可用」，而不是假装可用。
 *
 * <p>真正要守的两条：① 它不发起网络调用（否则切片测试依赖 Python 是否在跑）；
 * ② 它不能被抓去当默认实现 —— 生产默认是 {@link HttpPythonHealthProbe}，
 * 这条由 {@code HttpPythonHealthProbeTest} 与下面的装配测试分别盯住。
 */
class FakePythonHealthProbeTest {

    private final AiProperties properties = new AiProperties();

    @Test
    @DisplayName("默认地址是本机回环的 8200（不指向公网，也不注册 Nacos）")
    void defaultBaseUrlIsLoopback() {
        assertEquals("http://127.0.0.1:8200", properties.getPythonBaseUrl());
    }

    @Test
    @DisplayName("假探针如实返回不可用，并说明自己没去问")
    void reportsUnavailableWithReason() {
        PythonHealthProbe.ProbeResult result = new FakePythonHealthProbe(properties).probe();

        assertFalse(result.available(), "假探针不能假装下游可用");
        assertNotNull(result.reason(), "不可用时必须给出可读原因，否则运维无从下手");
        assertTrue(result.reason().contains("假探针"), "原因要指明这是假探针，别让人以为真连过");
        assertNotNull(result.reason());
        assertTrue(result.reason().contains(properties.getPythonBaseUrl()));
    }

    @Test
    @DisplayName("假探针不进组件扫描：没有被 @Component 之类标注，生产不会误装配")
    void isNotASpringComponent() {
        assertFalse(
                FakePythonHealthProbe.class.isAnnotationPresent(
                        org.springframework.stereotype.Component.class),
                "一旦给假探针加回 @Component，它就会和真实探针抢装配，/ai/health 又变永久假信号");
    }
}
