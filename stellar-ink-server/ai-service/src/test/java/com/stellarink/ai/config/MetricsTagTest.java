package com.stellarink.ai.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 公共标签 {@code application} 真的被打上了吗。
 *
 * <p><b>为什么要单独测这一条</b>：它是整个观测栈里**最隐蔽的一种失败**。
 * 标签没打上时服务照常启动、指标照常出现在 {@code /actuator/prometheus} 里、
 * 日志里一行异常都没有 —— 只是看板上那些按 {@code application} 切的曲线**全部消失**，
 * 而「图是空的」和「没有流量」长得一模一样。
 *
 * <p>用自己建一个探针计量器来断言，而不是去找 {@code process.uptime} 之类的自动指标：
 * 后者取决于有哪些自动配置生效，换一个 Spring Boot 小版本就可能不再存在，
 * 那样测试会从「断言标签」退化成「断言某个自动指标存在」，一失败就指向错误的方向。
 * 探针名唯一，只存在于测试 JVM 里，不会影响任何生产指标。
 *
 * <p>复用 {@code @SpringBootTest + @ActiveProfiles("unittest") + @Import(TestMasterKeyConfig.class)}
 * 这个组合是为了命中 Spring 的上下文缓存（本模块已有多个测试用同一组合），
 * 让这条断言几乎不额外花时间。
 */
@SpringBootTest
@ActiveProfiles("unittest")
@Import(TestMasterKeyConfig.class)
class MetricsTagTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("新注册的指标自动带上 application 标签（common-core 的 MetricsConfig 生效）")
    void commonTagIsAppliedToNewMeters() {
        String probe = "stellar.metrics.tag.probe";
        Counter counter = meterRegistry.counter(probe);
        counter.increment();

        assertNotNull(meterRegistry.find(probe).tag("application", "ai-service").counter(),
                "公共标签没打上：看板里所有按 application 切的曲线都会静默消失"
                        + "（按 application=ai-service 能找到，就同时证明了标签名与取值都对）");
        assertEquals(1d, counter.count());
    }
}
