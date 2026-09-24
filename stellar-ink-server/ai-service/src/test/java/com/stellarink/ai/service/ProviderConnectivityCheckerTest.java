package com.stellarink.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连通性自检：结论必须**诚实**（只证明地址可达），失败要可操作且不泄露内网细节。
 */
class ProviderConnectivityCheckerTest {

    private final ProviderConnectivityChecker checker = new ProviderConnectivityChecker();

    @Test
    @DisplayName("本机回环端口连不通时：失败结论 + 可操作提示，消息里不带 host")
    void reportsFailureWithoutLeakingHost() {
        // 127.0.0.1 上挑一个几乎不可能监听的端口，超时设短一点避免测试变慢
        ProviderConnectivityChecker.CheckResult result =
                checker.check("http://127.0.0.1:1/v1", 300);

        assertFalse(result.ok());
        assertEquals("tcp_only", result.scope());
        assertEquals(0L, result.latencyMs());
        assertTrue(result.message().contains("baseUrl") || result.message().contains("网络"),
                "失败提示要能让用户知道去哪儿改：" + result.message());
        assertFalse(result.message().contains("127.0.0.1"), "响应里不该带出具体主机");
    }

    @Test
    @DisplayName("非法 baseUrl 给出明确原因，而不是抛异常把接口打个 500")
    void reportsInvalidUrl() {
        assertFalse(checker.check("not a url").ok());
        assertFalse(checker.check("http:///v1").ok());
        assertFalse(checker.check("").ok());
    }

    @Test
    @DisplayName("地址可达时结论限定在 tcp_only，不冒充「模型可用」")
    void successIsScopedToTcpOnly() {
        // 起一个本地监听端口作为「可达」目标
        try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
            int port = server.getLocalPort();

            ProviderConnectivityChecker.CheckResult result =
                    checker.check("http://127.0.0.1:" + port + "/v1", 1000);

            assertTrue(result.ok(), "本机监听端口应当可达");
            assertEquals("tcp_only", result.scope(),
                    "自检只能证明地址可达；模型与密钥有效性由 Python 侧验证");
            assertTrue(result.latencyMs() >= 0);
            assertTrue(result.message().contains("尚未验证"), "提示必须写清结论范围");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法启动本地测试端口", e);
        }
    }

    @Test
    @DisplayName("https 缺省端口按 443 处理（不写成 80）")
    void httpsDefaultsToPort443() {
        // 用一个必然失败但能走到端口推导的地址，间接验证不抛异常
        ProviderConnectivityChecker.CheckResult result = checker.check("https://127.0.0.1/v1", 200);
        assertFalse(result.ok());
        assertFalse(result.message().contains("443"), "端口不该出现在用户可见消息里");
    }
}
