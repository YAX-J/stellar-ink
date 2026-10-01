package com.stellarink.ai.controller;

import com.stellarink.ai.stream.QaSseFrame;
import com.stellarink.ai.stream.QaStreamClient;
import com.stellarink.aiclient.dto.QaStreamRequestDTO;
import com.stellarink.ai.enums.AiCallScene;
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.dto.ai.AiAskDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;

/**
 * 星海问答的流式出口：把 Python 的 SSE 帧**逐帧**转给浏览器。
 *
 * <p>这一层只做协议转换，三件事：转发、记账、取消。**不解析事件体、不重新编码** ——
 * Python 的帧原样透传（{@link QaSseFrame#raw()}），少一层映射就少一处会与 Python 契约分叉的地方。
 *
 * <p>取消传播是这条链路的重点，也是它唯一容易做错的地方：
 * 浏览器关掉页面 → Spring 在下一次 {@code send} 时抛 {@link IOException}
 * → 这里立刻 {@code close()} 掉下游句柄 → 上游 HTTP 连接断开 → Python 的
 * {@code StreamingResponse} 生成器被关闭 → 模型停止生成。
 * 少了这一步，用户以为「关掉了」，模型还在按 token 花钱。
 *
 * <p>为什么不用 {@code SseEmitter}：那个类会**按事件名分帧**（`event:` 头 + data），
 * 而我们的协议刻意把类型放在 JSON 里（见 `docs/api/README.md`）。
 * 用 {@link ResponseBodyEmitter} 才能保证「Python 发什么，浏览器收到什么」。
 *
 * <p>为什么**不加** {@code @Async}：返回 {@code ResponseBodyEmitter} 时 MVC 本来就进入异步模式
 * （Servlet 异步 + 容器线程归还），而把整个方法丢到线程池只会引入第二个线程模型与新的竞态。
 * 代价是转发期间占一个容器线程 —— 当前量级（单机、读者问答）可以接受，
 * 真需要并发上千路时应当换成 WebFlux 或显式的事件循环，而不是在这里加个注解。
 */
@Slf4j
@RestController
@RequestMapping("/ai/qa")
@RequiredArgsConstructor
@Tag(name = "AI 星海问答", description = "登录用户就全站文章提问；流式版本逐帧转发引用与答案")
public class AiQaStreamController {

    /** 单次流式问答的整体上限：到点收尾并给前端一个可读的事件，而不是让连接悬着。 */
    private static final long STREAM_TIMEOUT_MS = 120_000L;

    private final QaStreamClient qaStreamClient;

    /** 调用账（E3-1）：流式这条路径原先**只在自己的注释里写着「记账」而并没有记** */
    private final AiUsageService usageService;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "就全站文章提问（SSE 流式）",
            description = "事件顺序固定 meta → citation* → delta* → done；error 为旁路事件（之后不会有 done）")
    public ResponseBodyEmitter stream(@Valid @RequestBody AiAskDTO request) {
        Long userId = AuthHelper.loginId();

        ResponseBodyEmitter emitter = new ResponseBodyEmitter(STREAM_TIMEOUT_MS);
        emitter.onCompletion(() -> log.debug("问答流正常结束：userId={}", userId));
        emitter.onTimeout(() -> log.warn("问答流超时被回收：userId={}", userId));
        // 客户端断开时 Spring 会调用它；真正的下游取消在 forwardFrames 的 IOException 分支里
        emitter.onError(error -> log.info("问答流被客户端中断：userId={} reason={}", userId, error.toString()));

        QaStreamRequestDTO internal = QaStreamRequestDTO.builder()
                .question(request.getQuestion().trim())
                .topK(request.getTopK())
                .build();
        forwardFrames(emitter, internal, userId);
        return emitter;
    }

    /**
     * 把下游帧逐条写进 emitter，并在任何退出路径上关掉下游。
     *
     * <p>{@code try-with-resources} 是刻意的：正常结束、上游异常、客户端断开三条路都要关；
     * 分开写三处 close 迟早漏一处，而漏掉的那处就是「关掉页面后模型继续生成」。
     */
    void forwardFrames(ResponseBodyEmitter emitter, QaStreamRequestDTO request, Long userId) {
        long started = System.currentTimeMillis();
        String lastType = QaSseFrame.UNKNOWN;
        try (QaStreamClient.Handle handle = qaStreamClient.open(request)) {
            for (QaSseFrame frame : handle) {
                lastType = frame.eventType();
                emitter.send(frame.raw(), MediaType.TEXT_EVENT_STREAM);
            }
            emitter.complete();
            log.info("AI 流式问答完成：userId={} lastEvent={}", userId, lastType);
            // 用量记「未计量」：它写在 done 帧的 JSON 里，而按既定设计 Java **不解析事件体**
            // （解析等于再抄一份 Python 的事件契约）。缺口由看板的 untokenizedCalls 如实暴露。
            usageService.recordSuccess(AiCallScene.QA_STREAM, null, started);
        } catch (IOException error) {
            // 浏览器断开（或 emitter 已超时）：**这里就是取消传播的落点**
            log.info("问答流写入失败，取消下游生成：userId={} reason={}", userId, error.getMessage());
            // 客户端主动断开**不算失败**：用户就是想停，把它记成 0 会把失败率抬高
            usageService.recordSuccess(AiCallScene.QA_STREAM, null, started);
            emitter.completeWithError(error);
        } catch (Exception error) {
            // 上游不可用：给一帧可读的 error，而不是让前端停在「生成中」
            log.warn("问答流上游失败：userId={} reason={}", userId, error.getMessage());
            usageService.recordFailure(AiCallScene.QA_STREAM, error, started);
            sendErrorFrame(emitter);
        }
    }

    private void sendErrorFrame(ResponseBodyEmitter emitter) {
        try {
            emitter.send(
                    "data: {\"type\":\"error\",\"code\":\"AI_UPSTREAM_UNAVAILABLE\","
                            + "\"message\":\"AI 服务暂时不可用，请稍后重试\"}\n\n",
                    MediaType.TEXT_EVENT_STREAM);
        } catch (Exception ignored) {
            // 连错误帧都发不出去（客户端已走）：无需再做什么，连接会被回收
        } finally {
            emitter.complete();
        }
    }
}
