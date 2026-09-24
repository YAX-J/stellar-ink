package com.stellarink.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * 模型端点的连通性自检（M1 之前的诚实版本）。
 *
 * <p>它只做一件事：**能不能建立 TCP 连接**。这能立刻抓出最常见的一类配置错误
 * ——baseUrl 写错、域名解析不了、公司网络封了出网——而不需要密钥、不发一个 Token。
 *
 * <p>它**不**代表「模型可用」：模型名写错、Key 无效、额度用尽都要真正调用一次才知道。
 * 那一步由 Python 侧执行（它才是调用方），本类在完成后的自检结论里明确标注
 * {@code tcp_only}，避免面板给出「可用」这种过头结论。
 */
@Slf4j
@Component
public class ProviderConnectivityChecker {

    /** 默认探测超时：自检是交互操作，超过 3 秒没连上就该告诉用户「不通」 */
    public static final int DEFAULT_TIMEOUT_MS = 3000;

    public CheckResult check(String baseUrl) {
        return check(baseUrl, DEFAULT_TIMEOUT_MS);
    }

    public CheckResult check(String baseUrl, int timeoutMs) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            return CheckResult.failed("baseUrl 不是合法地址");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return CheckResult.failed("baseUrl 里没有主机名");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);

        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
        } catch (Exception e) {
            // 失败原因要给得可操作，但不能把内网/代理细节写进响应
            log.warn("模型端点连通性自检失败：host={}, port={}, error={}", host, port, e.getClass().getSimpleName());
            return CheckResult.failed("连接失败：请检查 baseUrl、网络或代理设置");
        }
        long costMs = (System.nanoTime() - start) / 1_000_000;
        return CheckResult.ok(costMs);
    }

    /**
     * 自检结论。
     *
     * @param ok        TCP 是否可达
     * @param scope     结论范围，固定 {@code tcp_only}：只证明地址可达，不代表模型可用
     * @param latencyMs 建连耗时（毫秒）
     * @param message   可展示的中文说明
     */
    public record CheckResult(boolean ok, String scope, long latencyMs, String message) {

        public static CheckResult ok(long latencyMs) {
            return new CheckResult(true, "tcp_only", latencyMs, "地址可达（尚未验证模型与密钥）");
        }

        public static CheckResult failed(String message) {
            return new CheckResult(false, "tcp_only", 0L, message);
        }
    }
}
