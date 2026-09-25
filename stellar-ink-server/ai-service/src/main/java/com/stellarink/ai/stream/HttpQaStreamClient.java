package com.stellarink.ai.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarink.aiclient.constant.AiContractPaths;
import com.stellarink.aiclient.dto.QaStreamRequestDTO;
import com.stellarink.aiclient.signature.InternalRequestSigner;
import com.stellarink.ai.config.InternalCallerProvider;
import com.stellarink.ai.config.InternalSecretProvider;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 用 JDK 自带的 {@link HttpClient} 直连 Python 的 {@code /qa/stream}。
 *
 * <p>三件事与 Feign 那条通道**必须保持一致**（否则同一个故障在两条路径上表现不同）：
 * <ol>
 *   <li><b>签名头</b>：复用 {@link InternalRequestSigner}，标准串与
 *       {@code InternalSignatureFeignInterceptor} 完全相同（path 不含 query）；</li>
 *   <li><b>错误映射</b>：上游非 2xx 一律抛 503 业务异常，**不返回空流**
 *       ——空流会被前端当成「回答完了」，于是「服务坏了」伪装成「没有依据」；</li>
 *   <li><b>body 编码</b>：UTF-8，与 Feign 一致（两侧摘要按同一编码算）。</li>
 * </ol>
 *
 * <p>超时口径：连接超时短（内网，连不上就是坏了），**读超时用整体上限**。
 * 真正的「模型卡住」由 Python 侧与浏览器侧的超时兜底，这里只保证不会无限等。
 */
@Slf4j
@Component
public class HttpQaStreamClient implements QaStreamClient {

    private final InternalSecretProvider secretProvider;
    private final InternalCallerProvider callerProvider;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;

    public HttpQaStreamClient(
            InternalSecretProvider secretProvider,
            InternalCallerProvider callerProvider,
            ObjectMapper objectMapper,
            // 与 Feign（PythonAiClient）和探活（AiProperties）**必须同一个键**：
            // 曾经这里写的是 `ai.python.base-url`，而所有 yml 里配的是
            // `stellar.ink.ai.python-base-url` —— 那个键根本不存在，于是配置被静默忽略、
            // 永远走这里的默认值。本地碰巧两者都是 127.0.0.1:8200 所以看不出来，
            // 一到 Docker（AI_PYTHON_BASE_URL=http://stellar-ink-ai:8200）就是
            // 「探活说好的、功能全是坏的」这种分裂状态
            @Value("${stellar.ink.ai.python-base-url:http://127.0.0.1:8200}") String baseUrl,
            @Value("${ai.python.connect-timeout-ms:2000}") long connectTimeoutMs) {
        this.secretProvider = secretProvider;
        this.callerProvider = callerProvider;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                // 内网直连，重定向一定是配置错误：跟随只会把问题藏起来
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Handle open(QaStreamRequestDTO request) {
        InternalCallerProvider.Caller caller = callerProvider.current();
        String body = writeBody(request);
        Map<String, String> headers = secretProvider.signer().signRequest(
                "POST",
                AiContractPaths.QA_STREAM,
                body,
                caller.userId(),
                caller.role(),
                caller.traceId());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + AiContractPaths.QA_STREAM))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable("内部 AI 服务请求被中断", error);
        } catch (Exception error) {
            throw unavailable("无法连接内部 AI 服务", error);
        }

        if (response.statusCode() / 100 != 2) {
            // 读一小段就够定位（鉴权失败/契约不符），读全可能把大段 HTML 错误页灌进日志
            throw unavailable("内部 AI 服务返回 " + response.statusCode(), null);
        }
        return new HttpHandle(response.body());
    }

    private String writeBody(QaStreamRequestDTO request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception error) {
            // 序列化失败是本地契约问题：直接暴露，不要伪装成上游不可用
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR, "问答请求序列化失败");
        }
    }

    private static BusinessException unavailable(String message, Throwable cause) {
        BusinessException exception = BusinessExceptionHelper.of(ErrorCode.SERVICE_UNAVAILABLE, message);
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    /** 基于响应体流的句柄：迭代帧，close 即断开上游。 */
    private static final class HttpHandle implements Handle {

        private final BufferedReader reader;
        private final Iterator<QaSseFrame> frames;
        private boolean closed;

        private HttpHandle(InputStream body) {
            this.reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            this.frames = new FrameIterator(reader);
        }

        @Override
        public Iterator<QaSseFrame> iterator() {
            return frames;
        }

        @Override
        public void close() {
            if (closed) {
                // 正常结束与「浏览器断开」都可能触发关闭，重复调用必须无害
                return;
            }
            closed = true;
            try {
                reader.close();
            } catch (Exception error) {
                log.debug("关闭问答流时出错（可忽略）：{}", error.getMessage());
            }
        }
    }

    /**
     * 把 SSE 行流拼成帧。
     *
     * <p>规则来自 `docs/api/README.md` 的线格式：一个**数据帧**由 `data:` 行组成、以空行结束。
     * 必须按空行聚合后再交给上层，不能一行一行往浏览器写 ——
     * 拆开发送会让浏览器把 `data:` 与后续行当成两个不完整事件，出现「半截 JSON 解析失败」。
     *
     * <p>心跳（`: ping` 注释行）与事件名行**不是帧**，直接丢弃：它们只对「让代理别掐连接」有意义。
     * 一开始把所有非空行都当帧，于是每个心跳都被解析成 `unknown` 事件转发给前端 ——
     * 前端会收到一串莫名其妙的事件，日志里的 lastEvent 也永远不是真实的收尾类型。
     */
    static final class FrameIterator implements Iterator<QaSseFrame> {

        private final BufferedReader reader;
        private QaSseFrame next;

        FrameIterator(BufferedReader reader) {
            this.reader = reader;
        }

        @Override
        public boolean hasNext() {
            if (next == null) {
                next = readFrame();
            }
            return next != null;
        }

        @Override
        public QaSseFrame next() {
            if (!hasNext()) {
                throw new NoSuchElementException("问答流已结束");
            }
            QaSseFrame frame = next;
            next = null;
            return frame;
        }

        /** @return 下一个数据帧；流结束返回 null（也意味着可以关闭上游了） */
        private QaSseFrame readFrame() {
            StringBuilder buffer = new StringBuilder();
            boolean hasData = false;
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (hasData) {
                            // 空行是帧分隔符：原样保留结尾的换行，前端按它切帧
                            buffer.append('\n');
                            return QaSseFrame.of(buffer.toString());
                        }
                        // 注释/事件名攒成的空帧（心跳）：丢掉，继续读下一帧
                        buffer.setLength(0);
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        hasData = true;
                    }
                    buffer.append(line).append('\n');
                }
            } catch (Exception error) {
                // 连接被取消 / 上游挂断：当成流结束，让上层走正常收尾（不要吞掉成静默成功）
                log.debug("问答流读取中断：{}", error.getMessage());
            }
            if (hasData) {
                buffer.append('\n');
                return QaSseFrame.of(buffer.toString());
            }
            return null;
        }
    }
}
