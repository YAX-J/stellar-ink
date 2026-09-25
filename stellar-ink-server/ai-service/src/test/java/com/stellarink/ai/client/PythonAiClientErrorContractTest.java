package com.stellarink.ai.client;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.QaStreamRequestDTO;
import com.stellarink.aiclient.error.PythonApiException;
import com.stellarink.aiclient.signature.InternalRequestSigner;
import com.stellarink.ai.config.InternalCallerProvider;
import com.stellarink.ai.config.InternalSecretProvider;
import com.stellarink.ai.config.SaTokenCallerProvider;
import com.stellarink.ai.config.TestMasterKeyConfig;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.Role;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Python 的错误契约**真的穿过了 Feign**吗？—— 起真上下文、指向一个本地假 Python 来验。
 *
 * <p>这一条不是重复单测：`PythonErrorDecoder` 的单测只证明「给它一个 Response，它翻得对」，
 * 而真正会出错的是**装配**——解码器有没有被 Feign 取到、
 * `stellar.ink.ai.python-base-url` 这个键有没有被 `@FeignClient` 的占位符读到。
 * 这两件事都**不会报错**，只会静静退回默认值：
 * 退回默认解码器 ⇒ 用户看到「系统繁忙，请稍后重试」；
 * 退回默认地址 ⇒ 请求打到 127.0.0.1:8200（本地碰巧是对的，Docker 里必然错）。
 *
 * <p>所以假 Python 回一句**独一无二的哨兵消息**：断言里出现它就同时证明了
 * 「请求到了配置里的地址」与「上游消息被原样带回来了」。
 */
@SpringBootTest
@ActiveProfiles("unittest")
@Import(TestMasterKeyConfig.class)
class PythonAiClientErrorContractTest {

    /** 哨兵：只在本次测试的假 Python 上存在，真实 Python 不可能回这句话。 */
    private static final String SENTINEL_MESSAGE = "角色 embedding 尚未配置模型（哨兵测试消息）";

    private static HttpServer server;

    private static final AtomicInteger hits = new AtomicInteger();

    private static final AtomicReference<String> lastPath = new AtomicReference<>("");

    @BeforeAll
    static void startFakePython() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/qa", exchange -> {
            hits.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getPath());
            byte[] body = ("{\"code\":\"AI_BAD_REQUEST\",\"message\":\"" + SENTINEL_MESSAGE + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(400, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterAll
    static void stopFakePython() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 只改 `stellar.ink.ai.python-base-url` 一处：如果 Feign 读的是别的键（或写死的默认值），
     * 请求就不会打到这个假 Python 上，测试立刻红。
     */
    @DynamicPropertySource
    static void pointAtFakePython(DynamicPropertyRegistry registry) {
        registry.add("stellar.ink.ai.python-base-url", () -> "http://127.0.0.1:" + port());
    }

    private static int port() {
        if (server == null) {
            throw new IllegalStateException("假 Python 还没起来");
        }
        return server.getAddress().getPort();
    }

    @Autowired
    private PythonAiClient pythonAiClient;

    /**
     * 调用者身份用桩替掉。
     *
     * <p>真实实现（{@code SaTokenCallerProvider}）要从 Sa-Token 取当前登录用户，
     * 而这里没有请求上下文，它会抛「登录状态已失效」—— 那是**测试环境**的限制，
     * 与本次要验的东西（错误体解码、地址取值）无关。
     * 签名头本身由 {@code InternalSignatureFeignInterceptorTest} 单独覆盖。
     */
    @MockBean
    private SaTokenCallerProvider callerProvider;

    /**
     * 签名器也换成桩：签名密钥**只从环境变量读**（fail-closed，这是有意的），
     * 测试环境没有那个变量 —— 真去要密钥只会得到 {@code SecretMissingException}，
     * 与本次要验的东西无关。签名算法本身由 `InternalRequestSignerTest` 覆盖。
     */
    @MockBean
    private InternalSecretProvider secretProvider;

    @BeforeEach
    void stubCaller() {
        when(callerProvider.current())
                .thenReturn(new InternalCallerProvider.Caller(1L, Role.ADMIN, "test-trace"));
        when(secretProvider.signer())
                .thenReturn(InternalRequestSigner.fromSecret("test-only-internal-secret-0123456789"));
    }

    private static QaStreamRequestDTO question() {
        QaStreamRequestDTO request = new QaStreamRequestDTO();
        request.setQuestion("一年写十八万字的方法是什么？");
        request.setTopK(3);
        return request;
    }

    @Test
    @DisplayName("Python 的可读 400：经 Feign 之后仍然是那句话，而不是「系统繁忙」")
    void readableErrorSurvivesTheFeignRoundTrip() {
        int before = hits.get();

        PythonApiException error = assertThrows(
                PythonApiException.class, () -> pythonAiClient.qaAsk(question()));

        // 计数器是两个用例共享的，所以比的是「这一次多了一跳」，不是绝对值
        assertEquals(before + 1, hits.get(), "请求应当打到配置里的地址（假 Python）");
        assertEquals("/qa", lastPath.get());
        assertEquals(400, error.getStatus());
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), error.getCode());
        assertTrue(
                error.getMessage().contains(SENTINEL_MESSAGE),
                "上游给的可操作提示被丢掉了，用户只会看到「系统繁忙」：" + error.getMessage());
    }

    @Test
    @DisplayName("配置里的地址确实被用上了：换端口就打到新端口，不是写死的 127.0.0.1:8200")
    void configuredAddressIsActuallyUsed() {
        assertTrue(
                port() != 8200,
                "假 Python 应该在一个随机端口上，否则这条断言证明不了「配置生效」");
        int before = hits.get();

        assertThrows(PythonApiException.class, () -> pythonAiClient.qaAsk(question()));

        assertEquals(before + 1, hits.get(), "请求没有到达配置里的地址");
    }
}
