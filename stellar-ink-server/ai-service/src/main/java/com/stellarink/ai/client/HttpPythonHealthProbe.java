package com.stellarink.ai.client;

import com.stellarink.ai.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * 真实的 Python 探活：一次短超时的 {@code GET /health}。
 *
 * <p><b>为什么现在才做</b>：M0 留了一个 {@code FakePythonHealthProbe} 恒定返回「未就绪」，
 * 当时的理由是「如实上报比假装健康更安全」。理由没错，但它成了**永久假信号**：
 * Python 明明在 8200 上跑着，{@code /ai/health} 依旧报 {@code available=false}，
 * 于是「用探活判断链路通没通」这件事彻底失效 —— 而排障时最需要的恰恰是这个判断。
 *
 * <p>接口是公开的（{@code /health} 在 Python 侧的公开白名单里），因此**不带内部签名**：
 * 探活要能在「密钥配错」的情况下仍然回答「Python 活着吗」，否则它会把两类问题混成一个。
 *
 * <p>超时必须短：探活是给人和编排看的，卡住 30 秒不如立刻说「连不上」。
 */
@Slf4j
public class HttpPythonHealthProbe implements PythonHealthProbe {

    /** 探活超时：短到能立刻给出结论，长到能容忍一次内网往返。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final AiProperties properties;
    private final RestClient client;

    public HttpPythonHealthProbe(AiProperties properties) {
        this(properties, defaultClient(properties));
    }

    HttpPythonHealthProbe(AiProperties properties, RestClient client) {
        this.properties = properties;
        this.client = client;
    }

    private static RestClient defaultClient(AiProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return RestClient.builder()
                .baseUrl(properties.getPythonBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public ProbeResult probe() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = client.get()
                    .uri("/health")
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                // 200 但空体：说明对面不是我们期望的服务（例如被别的进程占了口）
                return ProbeResult.unavailable("Python 探活返回空响应，端口可能被其它进程占用");
            }
            String service = asText(body.get("service"));
            String version = asText(body.get("version"));
            // `status` 是 Python 自己报的健康结论：只有它说 ok 才算可用
            String status = asText(body.get("status"));
            if (status != null && !"ok".equalsIgnoreCase(status)) {
                return ProbeResult.unavailable("Python 自报状态为 " + status);
            }
            return ProbeResult.available(service, version);
        } catch (RuntimeException error) {
            // 原因只进日志与运维视角：**不回显内网地址与异常细节**给公开响应，
            // 公开响应由 AiHealthController 统一写成「下游 AI 编排服务未就绪」
            log.debug("Python 探活失败：{} {}", properties.getPythonBaseUrl(), error.toString());
            return ProbeResult.unavailable("无法连接 Python 服务：" + error.getClass().getSimpleName());
        }
    }

    /** 只认 String：Python 回了数字也不会把探活搞崩。 */
    private static String asText(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /**
     * 装配真实探活。
     *
     * <p>用 {@code @ConditionalOnMissingBean}：测试切片可以塞一个自己的实现，
     * 而生产不必知道这里有个开关 —— 真实探活是**默认**，不是需要显式打开的功能。
     */
    @Configuration
    static class Config {

        @Bean
        @ConditionalOnMissingBean(PythonHealthProbe.class)
        PythonHealthProbe pythonHealthProbe(AiProperties properties) {
            return new HttpPythonHealthProbe(properties);
        }
    }
}
