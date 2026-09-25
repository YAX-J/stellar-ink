package com.stellarink.ai.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarink.aiclient.dto.QaStreamRequestDTO;
import com.stellarink.aiclient.signature.InternalRequestSigner;
import com.stellarink.ai.config.InternalCallerProvider;
import com.stellarink.ai.config.InternalSecretProvider;
import com.stellarink.sharedmodel.enums.Role;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式客户端的真实 HTTP 往返测试。
 *
 * <p>为什么起一个真的 {@link HttpServer} 而不是 mock HTTP 层：
 * 这一刀的全部价值在于「**逐帧到达**」与「**close 真的断开上游**」，
 * 而这两件事都发生在 socket 与 flush 的时序里 —— mock 掉之后测的只是我们自己的假设。
 * JDK 自带的 HttpServer 不引任何依赖，代价只有几十行样板。
 *
 * <p>签名密钥通过覆写 {@link InternalSecretProvider#signer()} 注入（生产只从环境变量读）：
 * 这样测试不必改 JVM 环境变量，也不会因为「环境里正好没配密钥」而失败。
 */
class HttpQaStreamClientTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef";

    private HttpServer server;
    private final List<String> receivedPaths = new ArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static InternalSecretProvider fixedSecretProvider() {
        return new InternalSecretProvider() {
            @Override
            public InternalRequestSigner signer() {
                return InternalRequestSigner.fromSecret(SECRET);
            }
        };
    }

    private static InternalCallerProvider fixedCaller() {
        return () -> new InternalCallerProvider.Caller(7L, Role.READER, "trace-stream");
    }

    private static QaStreamRequestDTO request() {
        return QaStreamRequestDTO.builder().question("一年写十八万字的方法是什么？").topK(5).build();
    }

    /**
     * 起一个只会写给定内容的假 Python 服务。
     *
     * @param firstFrameFlushed 第一帧写出后放行（测试用它确认「确实拿到东西了」）
     * @param clientDisconnected 客户端断开后置位（这是「取消传到上游」的观测点）
     */
    private HttpQaStreamClient clientFor(
            String body, int status, CountDownLatch firstFrameFlushed, AtomicBoolean clientDisconnected)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/qa/stream", exchange -> handle(exchange, body, status, firstFrameFlushed, clientDisconnected));
        server.start();
        return new HttpQaStreamClient(
                fixedSecretProvider(),
                fixedCaller(),
                new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                1000L);
    }

    private void handle(
            HttpExchange exchange,
            String body,
            int status,
            CountDownLatch firstFrameFlushed,
            AtomicBoolean clientDisconnected) throws IOException {
        receivedPaths.add(exchange.getRequestURI().getPath());
        if (status != 200) {
            byte[] payload = "{\"code\":\"AI_BAD_REQUEST\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        // 0 = 分块传输：不预先声明长度，服务端才能一段段推
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String line : body.split("\n", -1)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (firstFrameFlushed != null && line.startsWith("data:")) {
                    firstFrameFlushed.countDown();
                }
                // 客户端断开后继续写会抛异常：这正是「取消传播到上游」的观测点
                Thread.sleep(5);
            }
        } catch (IOException disconnected) {
            if (clientDisconnected != null) {
                clientDisconnected.set(true);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("按空行切成帧，且事件类型被解析出来")
    void groupsFramesByBlankLine() throws Exception {
        String stream = """
                data: {"type": "meta", "model": "fake"}

                data: {"type": "citation", "citation": {"postId": 1}}

                data: {"type": "delta", "text": "每天五百字。"}

                data: {"type": "done", "answer": "每天五百字。"}

                """;
        HttpQaStreamClient client = clientFor(stream, 200, null, null);

        List<QaSseFrame> frames = new ArrayList<>();
        try (QaStreamClient.Handle handle = client.open(request())) {
            handle.forEach(frames::add);
        }

        assertEquals(List.of("meta", "citation", "delta", "done"),
                frames.stream().map(QaSseFrame::eventType).toList());
        assertEquals("/qa/stream", receivedPaths.get(0), "路径必须与 Python 契约一致（签名也覆盖它）");
        assertTrue(frames.get(0).raw().startsWith("data: {"), "转发的是原文，不是重新序列化的结果");
        assertTrue(frames.get(3).raw().endsWith("\n\n"), "帧必须以空行结束，否则前端会一直等下一帧");
    }

    @Test
    @DisplayName("缺了结尾空行的最后一帧也要吐出来")
    void flushesUnterminatedTail() throws Exception {
        HttpQaStreamClient client = clientFor("data: {\"type\": \"done\", \"answer\": \"好\"}", 200, null, null);

        List<QaSseFrame> frames = new ArrayList<>();
        try (QaStreamClient.Handle handle = client.open(request())) {
            handle.forEach(frames::add);
        }

        assertEquals(1, frames.size(), "上游断在半路时不能把最后一段丢掉");
        assertEquals("done", frames.get(0).eventType());
    }

    @Test
    @DisplayName("上游非 2xx：抛业务异常，不返回空流")
    void upstreamFailureIsNotAnEmptyStream() throws Exception {
        HttpQaStreamClient client = clientFor("", 503, null, null);

        Exception error = assertThrows(Exception.class, () -> client.open(request()));
        assertTrue(error.getMessage().contains("503"),
                "错误里要有可定位的状态码，否则排障只能猜（实际：" + error.getMessage() + "）");
    }

    @Test
    @DisplayName("close 会真的断开上游：这是「关掉页面即停止生成」的落点")
    void closeDisconnectsUpstream() throws Exception {
        StringBuilder longStream = new StringBuilder();
        for (int index = 0; index < 400; index++) {
            longStream.append("data: {\"type\": \"delta\", \"text\": \"第").append(index).append("段\"}\n\n");
        }
        CountDownLatch firstFrame = new CountDownLatch(1);
        AtomicBoolean disconnected = new AtomicBoolean(false);
        HttpQaStreamClient client = clientFor(longStream.toString(), 200, firstFrame, disconnected);

        try (QaStreamClient.Handle handle = client.open(request())) {
            Iterator<QaSseFrame> frames = handle.iterator();
            assertTrue(frames.hasNext(), "至少要拿到第一帧");
            assertEquals("delta", frames.next().eventType());
            assertTrue(firstFrame.await(3, TimeUnit.SECONDS));
        }

        // 服务端还有几百帧要写：只有连接真的断了才会走进它的异常分支
        assertTrue(waitFor(disconnected::get),
                "close() 没有断开上游连接：客户端侧「取消」了，上游却还在继续生成");
    }

    @Test
    @DisplayName("close 可以重复调用（正常收尾与断开取消会各来一次）")
    void closeIsIdempotent() throws Exception {
        HttpQaStreamClient client = clientFor("data: {\"type\": \"done\"}\n\n", 200, null, null);

        QaStreamClient.Handle handle = client.open(request());
        handle.close();
        handle.close();
    }

    @Test
    @DisplayName("心跳与坏行都不影响后续帧：注释丢掉，数据帧原样转发，类型解析不出就标 unknown")
    void malformedFrameDoesNotBreakTheStream() throws Exception {
        String stream = """
                : ping

                data: {"type": "delta", "text": "好"}

                data: 这不是 JSON

                data: {"type": "done"}

                """;
        HttpQaStreamClient client = clientFor(stream, 200, null, null);

        List<QaSseFrame> frames = new ArrayList<>();
        try (QaStreamClient.Handle handle = client.open(request())) {
            handle.forEach(frames::add);
        }

        // 心跳（`: ping`）不是事件：转发它会让前端收到一串幽灵事件，
        // 日志里的「最后一个事件」也永远不是真实的收尾类型
        assertEquals(List.of("delta", "unknown", "done"),
                frames.stream().map(QaSseFrame::eventType).toList());
        assertFalse(frames.get(1).raw().isBlank(), "坏帧也要原样转发，否则前端与日志都看不到它");
    }

    private static boolean waitFor(BooleanSupplier condition) {
        for (int attempt = 0; attempt < 60; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
