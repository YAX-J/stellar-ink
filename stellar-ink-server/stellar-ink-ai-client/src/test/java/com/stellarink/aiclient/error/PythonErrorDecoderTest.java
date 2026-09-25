package com.stellarink.aiclient.error;

import com.stellarink.sharedmodel.enums.ErrorCode;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Python 错误体的解码：**可读的错误必须原样到用户面前**。
 *
 * <p>这条链路上出过一次真实的坑（实测确认）：Python 把「没配模型」做成可读的 400，
 * 但 Java 侧没有 ErrorDecoder，用户最终看到的是 {@code code=500「系统繁忙，请稍后重试」}——
 * 于是「去面板配一个角色」这件三十秒的事变成了「服务坏了」。
 */
class PythonErrorDecoderTest {

    /** 只回一段内存字节的最小 {@link Response.Body}（不引任何测试依赖）。 */
    private static final class BytesBody implements Response.Body {

        private final byte[] bytes;

        private BytesBody(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public Integer length() {
            return bytes.length;
        }

        @Override
        public boolean isRepeatable() {
            return true;
        }

        @Override
        public InputStream asInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public java.io.Reader asReader(java.nio.charset.Charset charset) {
            return new java.io.InputStreamReader(asInputStream(), charset);
        }

        @Override
        public void close() {
            // 内存字节流：无需关闭
        }
    }

    private static Response response(int status, String body) {
        return Response.builder()
                .status(status)
                .reason("test")
                .headers(Map.of())
                .request(Request.create(
                        Request.HttpMethod.POST, "/qa", Map.of(), null, StandardCharsets.UTF_8, null))
                .body(body == null ? null : new BytesBody(body.getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    private final PythonErrorDecoder decoder = new PythonErrorDecoder();

    @Test
    @DisplayName("Python 的可读 400：消息原样透出，错误码是「参数错误」而不是「服务不可用」")
    void keepsTheReadableMessageOfABadRequest() {
        String message = "角色 embedding 尚未配置模型（请在「AI 实验室 → 模型配置」里填写该角色的端点与密钥）";

        Exception decoded = decoder.decode(
                "PythonAiClient#qaAsk",
                response(400, "{\"code\":\"AI_BAD_REQUEST\",\"message\":\"" + message + "\"}"));

        assertTrue(decoded instanceof PythonApiException, "应当是携带上游消息的业务异常");
        PythonApiException api = (PythonApiException) decoded;
        assertEquals(400, api.getStatus());
        assertEquals(
                ErrorCode.PARAM_ERROR.getCode(), api.getCode(), "配置问题要报参数错误，别报服务不可用");
        assertEquals(message, api.getMessage(), "这句话是可操作的，必须一字不改地给用户");
    }

    @Test
    @DisplayName("401/403 不能映射成「未授权」：那是内部签名问题，会把用户清出登录态")
    void internalSignatureFailuresMustNotLogTheUserOut() {
        PythonApiException api = (PythonApiException) decoder.decode(
                "PythonAiClient#qaAsk",
                response(401, "{\"code\":\"AI_UNAUTHORIZED\",\"message\":\"内部请求校验失败\"}"));

        assertFalse(
                api.getCode().equals(ErrorCode.UNAUTHORIZED.getCode()),
                "前端用 code===401 判「登录失效」并清会话 —— 内部签名坏了不该把用户踢出去");
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE.getCode(), api.getCode());
    }

    @Test
    @DisplayName("上游 5xx 与限流：报服务不可用，但带上上游那句「稍后重试」")
    void upstreamFailuresKeepTheirOwnMessage() {
        PythonApiException rateLimited = (PythonApiException) decoder.decode(
                "PythonAiClient#qaAsk",
                response(429, "{\"code\":\"AI_RATE_LIMITED\",\"message\":\"模型服务限流，请稍后重试\"}"));
        PythonApiException upstream = (PythonApiException) decoder.decode(
                "PythonAiClient#qaAsk",
                response(502, "{\"code\":\"AI_UPSTREAM_UNAVAILABLE\",\"message\":\"模型服务响应超时\"}"));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE.getCode(), rateLimited.getCode());
        assertEquals("模型服务限流，请稍后重试", rateLimited.getMessage());
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE.getCode(), upstream.getCode());
        assertEquals("模型服务响应超时", upstream.getMessage());
    }

    @Test
    @DisplayName("契约外的错误体（HTML / 空体 / FastAPI 的 detail）：退回默认行为，绝不回显上游原文")
    void unknownBodiesFallBackWithoutEchoingThem() {
        assertFalse(
                decoder.decode("PythonAiClient#qaAsk", response(500, "<html>502 Bad Gateway</html>"))
                        instanceof PythonApiException);
        assertFalse(
                decoder.decode("PythonAiClient#qaAsk", response(500, null))
                        instanceof PythonApiException);
        assertFalse(
                decoder.decode(
                                "PythonAiClient#qaAsk",
                                response(422, "{\"detail\":[{\"loc\":[\"body\"]}]}"))
                        instanceof PythonApiException);
    }

    @Test
    @DisplayName("超长消息截断：错误体是给用户看的，不该把整篇上游报文搬进界面")
    void truncatesAbsurdlyLongMessages() throws IOException {
        String longMessage = "很长的提示".repeat(200);

        PythonApiException api = (PythonApiException) decoder.decode(
                "PythonAiClient#qaAsk",
                response(400, "{\"code\":\"AI_BAD_REQUEST\",\"message\":\"" + longMessage + "\"}"));

        assertNotNull(api.getMessage());
        assertTrue(api.getMessage().length() < longMessage.length());
        assertTrue(api.getMessage().endsWith("…"));
    }
}
