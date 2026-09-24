package com.stellarink.ai.client;

import com.stellarink.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * M0 的 Fake 探活：**如实上报「下游未接线」**，不假装健康。
 *
 * <p>为什么不直接返回 {@code available=true}：那会让 {@code /ai/health} 在 M0 说谎，
 * 掩盖「Python 链路还没接」这一事实；而返回 false + 原因，既保证契约稳定，
 * 又让人一眼看出当前处于哪个里程碑。
 *
 * <p>M1 用真实 HTTP 探活替换本类（`stellar-ink-ai-client` 的 {@code health()}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FakePythonHealthProbe implements PythonHealthProbe {

    private final AiProperties properties;

    @Override
    public ProbeResult probe() {
        String reason = "M0 尚未接线：M1 将改为真实调用 " + properties.getPythonBaseUrl() + "/health";
        log.debug("Python 探活（fake）：{}", reason);
        return ProbeResult.unavailable(reason);
    }
}
