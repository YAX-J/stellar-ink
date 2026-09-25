package com.stellarink.ai.client;

import com.stellarink.ai.config.AiProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Python 探活：**必须真的去问，并把结论如实翻译**。
 *
 * <p>这一刀的存在理由就是「原来那个探针恒定返回 false」——
 * 所以这里既验「通了要报通」，也验「各种坏形态不能报成通」。
 * 用真实的 {@link HttpServer} 而不是 mock HTTP 层：探活的价值全在「真的能连上吗」，
 * mock 掉之后测的只是自己的假设（同一个理由见 {@code HttpQaStreamClientTest}）。
 */
class HttpPythonHealthProbeTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 起一个只回固定内容的假 Python；{@code delayMs} 用来验证读超时。 */
    private AiProperties propertiesFor(int status, String body, long delayMs) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        AiProperties properties = new AiProperties();
        properties.setPythonBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return properties;
    }

    private PythonHealthProbe.ProbeResult probe(int status, String body, long delayMs) throws IOException {
        AiProperties properties = propertiesFor(status, body, delayMs);
        // 超时调短一点，让「读超时」用例跑得快（生产值见 HttpPythonHealthProbe 的常量）
        RestClient client = RestClient.builder()
                .baseUrl(properties.getPythonBaseUrl())
                .requestFactory(factory())
                .build();
        return new HttpPythonHealthProbe(properties, client).probe();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory factory() {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofMillis(500).toMillis());
        f.setReadTimeout((int) Duration.ofMillis(500).toMillis());
        return f;
    }

    @Test
    @DisplayName("Python 活着：报可用，并把服务名与版本带回来")
    void reportsAvailableWithIdentity() throws Exception {
        PythonHealthProbe.ProbeResult result = probe(
                200,
                "{\"service\":\"stellar-ink-ai\",\"version\":\"0.1.0\",\"status\":\"ok\",\"env\":\"dev\"}",
                0);

        assertTrue(result.available(), "Python 明明活着，探活却报不可用 —— 这正是原 Fake 探针的病");
        assertEquals("stellar-ink-ai", result.service());
        assertEquals("0.1.0", result.version());
        assertNull(result.reason(), "可用时不该有原因");
    }

    @Test
    @DisplayName("Python 自报非 ok：不算可用（能连上 ≠ 健康）")
    void reportsUnavailableWhenPythonSaysNotOk() throws Exception {
        PythonHealthProbe.ProbeResult result = probe(
                200, "{\"service\":\"stellar-ink-ai\",\"status\":\"degraded\"}", 0);

        assertFalse(result.available());
        assertNotNull(result.reason());
        assertTrue(result.reason().contains("degraded"), "原因要带上 Python 自报的状态：" + result.reason());
    }

    @Test
    @DisplayName("端口被别的进程占用（200 但空体）：报不可用，别硬说成健康")
    void reportsUnavailableOnEmptyBody() throws Exception {
        PythonHealthProbe.ProbeResult result = probe(200, "", 0);

        assertFalse(result.available());
        assertNotNull(result.reason());
        assertTrue(result.reason().contains("端口"), result.reason());
    }

    @Test
    @DisplayName("连不上：报不可用，且原因是可读的（不外带堆栈）")
    void reportsUnavailableWhenConnectionFails() {
        AiProperties properties = new AiProperties();
        // 1 号端口不会有人监听：连不上是可预期结果
        properties.setPythonBaseUrl("http://127.0.0.1:1");
        RestClient client = RestClient.builder()
                .baseUrl(properties.getPythonBaseUrl())
                .requestFactory(factory())
                .build();

        PythonHealthProbe.ProbeResult result = new HttpPythonHealthProbe(properties, client).probe();

        assertFalse(result.available());
        assertNotNull(result.reason());
        assertTrue(result.reason().startsWith("无法连接 Python 服务"), result.reason());
        assertFalse(result.reason().contains("\n"), "原因用于一行日志，不该带堆栈换行");
    }

    @Test
    @DisplayName("超时：同样归到「不可用」，不让探活把请求挂住")
    void reportsUnavailableOnTimeout() throws Exception {
        PythonHealthProbe.ProbeResult result = probe(200, "{\"status\":\"ok\"}", 1_200);

        assertFalse(result.available(), "读超时应当报不可用，而不是无限等下去");
        assertNotNull(result.reason());
    }

    @Test
    @DisplayName("没有 status 字段时按可用处理：老版本 Python 只回 service/version 也算活着")
    void toleratesMissingStatusField() throws Exception {
        PythonHealthProbe.ProbeResult result = probe(200, "{\"service\":\"stellar-ink-ai\"}", 0);

        assertTrue(result.available());
        assertEquals("stellar-ink-ai", result.service());
    }

    @Test
    @DisplayName("非字符串字段不炸：探活不能因为对面字段类型变了就抛异常")
    void toleratesUnexpectedTypes() throws Exception {
        PythonHealthProbe.ProbeResult result = probe(200, "{\"service\":123,\"version\":null}", 0);

        assertTrue(result.available());
        assertNull(result.service(), "非字符串一律当作取不到，而不是抛异常");
    }
}
