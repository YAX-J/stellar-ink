package com.stellarink.ai.client;

import com.stellarink.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 恒定报告「下游不可用」的假探针：**只给测试与演示用**。
 *
 * <p>⚠️ <b>它不再是默认实现</b>（{@link HttpPythonHealthProbe} 才是）。留着它是为了两件事：
 * <ol>
 *   <li>切片测试要一个**不发起网络调用**的探针，否则测试会依赖 Python 是否在跑；</li>
 *   <li>需要演示「下游不可用」时不必真去关 Python。</li>
 * </ol>
 *
 * <p>为什么必须降级为「非默认」：它恒定返回 {@code available=false}，
 * 于是 Python 明明在跑、{@code /ai/health} 却永远说「未就绪」——
 * 探活本是排障的第一手信号，一个恒假的信号比没有信号更糟（会让人去查不存在的问题）。
 * 生产装配见 {@link HttpPythonHealthProbe.Config}：真实探活是默认，本类不在组件扫描范围内
 * （没有 {@code @Component}，也没有 {@code @ConditionalOnMissingBean} 把它兜进来）。
 */
@Slf4j
@RequiredArgsConstructor
public class FakePythonHealthProbe implements PythonHealthProbe {

    private final AiProperties properties;

    @Override
    public ProbeResult probe() {
        String reason = "假探针（测试/演示用）：未真实调用 " + properties.getPythonBaseUrl() + "/health";
        log.debug("Python 探活（fake）：{}", reason);
        return ProbeResult.unavailable(reason);
    }
}
