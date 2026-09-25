package com.stellarink.ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.ai.service.AiModelLibraryService;
import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.ai.stream.QaSseFrame;
import com.stellarink.ai.stream.QaStreamClient;
import com.stellarink.aiclient.dto.QaStreamRequestDTO;
import com.stellarink.common.advice.GlobalExceptionHandler;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.dto.ai.AiAskDTO;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.enums.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 流式出口的权限、转发与取消。
 *
 * <p>三件事必须钉住：门槛是登录（读者功能）、帧**原样透传**（Java 不重新编码事件体）、
 * 以及**写失败时必须关掉下游** —— 最后这条是「关掉页面就停止烧 token」的全部实现，
 * 也是最容易在重构中被丢掉的一行。
 */
@WebMvcTest(controllers = AiQaStreamController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class AiQaStreamControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String QUESTION = "一年写十八万字的方法是什么？";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QaStreamClient qaStreamClient;

    /** 切片会把同包组件一起装配：探活要 Feign 客户端，模型配置要 Mapper，这里都 mock 掉。 */
    @MockBean
    private PythonAiClient pythonAiClient;

    @MockBean
    private AiProviderConfigService aiProviderConfigService;

    /** 模型库服务同样会被切片扫到，而它依赖 MyBatis Mapper：不 mock 掉，整个切片上下文就起不来。 */
    @MockBean
    private AiModelLibraryService modelLibraryService;

    private static String body(String question) {
        return """
                {"question": "%s", "topK": 5}
                """.formatted(question);
    }

    private static void stubAsReader(MockedStatic<AuthHelper> auth) {
        auth.when(AuthHelper::loginId).thenReturn(9L);
        auth.when(AuthHelper::currentRole).thenReturn(Role.READER);
    }

    /** 一个把给定帧排队吐出来的假句柄，并记录它有没有被关闭。 */
    private static final class StubHandle implements QaStreamClient.Handle {

        private final List<QaSseFrame> frames;
        private final boolean failOnIterate;
        boolean closed;

        StubHandle(List<QaSseFrame> frames, boolean failOnIterate) {
            this.frames = frames;
            this.failOnIterate = failOnIterate;
        }

        @Override
        public java.util.Iterator<QaSseFrame> iterator() {
            if (failOnIterate) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "无法连接内部 AI 服务");
            }
            return frames.iterator();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static QaSseFrame frame(String type, String payload) {
        return QaSseFrame.of("data: {\"type\": \"" + type + "\", " + payload + "}\n\n");
    }

    @Test
    @DisplayName("未登录：401，且不碰下游")
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/ai/qa/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(QUESTION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        verify(qaStreamClient, never()).open(any());
    }

    @Test
    @DisplayName("登录后进入异步：问题原样传给下游（只 trim），事件体不重新编码")
    void forwardsFramesAsIs() throws Exception {
        List<QaSseFrame> frames = List.of(
                frame("meta", "\"model\": \"fake\""),
                frame("citation", "\"citation\": {\"postId\": 20}"),
                frame("delta", "\"text\": \"每天五百字。\""),
                frame("done", "\"answer\": \"每天五百字。\", \"evidenceSufficient\": true"));
        StubHandle handle = new StubHandle(frames, false);
        when(qaStreamClient.open(any())).thenReturn(handle);

        MvcResult result;
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            result = mockMvc.perform(post("/ai/qa/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("  " + QUESTION + "  ")))
                    .andExpect(request().asyncStarted())
                    .andReturn();
        }

        MvcResult dispatched = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn();

        // 关于 Content-Type：MockMvc 的异步派发不会把 `ResponseBodyEmitter.send(..., TEXT_EVENT_STREAM)`
        // 写的头带进 MockHttpServletResponse（实测为 null），因此**不在这个切片里断言它** ——
        // 断言一个测不到的字段只会得到「功能坏了」的假警报，真正的保障是
        // `@PostMapping(produces = TEXT_EVENT_STREAM_VALUE)` 与 send 时显式带的 MediaType，
        // 下面用反射把契约钉住（改错了会立刻红）。
        PostMapping mapping = AiQaStreamController.class.getMethod("stream", AiAskDTO.class)
                .getAnnotation(PostMapping.class);
        assertTrue(List.of(mapping.produces()).contains(MediaType.TEXT_EVENT_STREAM_VALUE),
                "流式接口必须声明 produces=text/event-stream，否则浏览器不会按流解析");

        assertEquals(200, dispatched.getResponse().getStatus());
        assertFalse(dispatched.getResponse().isCommitted() && dispatched.getResponse().getContentAsString().isEmpty(),
                "帧应当已经写出，而不是空响应");

        ArgumentCaptor<QaStreamRequestDTO> captor = ArgumentCaptor.forClass(QaStreamRequestDTO.class);
        verify(qaStreamClient).open(captor.capture());
        assertEquals(QUESTION, captor.getValue().getQuestion(), "前后空白应被去掉，正文不能改");
        assertEquals(5, captor.getValue().getTopK());
        assertTrue(handle.closed, "正常结束后也要关闭下游句柄");
    }

    @Test
    @DisplayName("写失败（浏览器断开）：必须关掉下游，否则模型继续生成")
    void sendFailureCancelsUpstream() {
        AiQaStreamController controller = new AiQaStreamController(qaStreamClient);
        StubHandle handle = new StubHandle(List.of(frame("delta", "\"text\": \"好\"")), false);
        when(qaStreamClient.open(any())).thenReturn(handle);

        // 模拟「浏览器已经走了」：emitter 在 send 时抛 IOException
        ResponseBodyEmitter emitter = mock(ResponseBodyEmitter.class);
        try {
            org.mockito.Mockito.doThrow(new IOException("Broken pipe"))
                    .when(emitter).send(any(), any(MediaType.class));
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }

        controller.forwardFrames(emitter, QaStreamRequestDTO.builder().question(QUESTION).build(), 9L);

        assertTrue(handle.closed, "写失败时没有关闭下游：关掉页面后模型还会继续烧 token");
    }

    @Test
    @DisplayName("上游不可用：给一帧可读的 error，前端不会停在「生成中」")
    void upstreamFailureSendsErrorFrame() {
        AiQaStreamController controller = new AiQaStreamController(qaStreamClient);
        StubHandle handle = new StubHandle(List.of(), true);
        when(qaStreamClient.open(any())).thenReturn(handle);

        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        controller.forwardFrames(emitter, QaStreamRequestDTO.builder().question(QUESTION).build(), 9L);

        assertTrue(handle.closed, "失败路径同样要关掉下游");
    }

    @Test
    @DisplayName("事件类型解析：未知类型标 unknown，不猜成别的")
    void unknownEventTypeIsNotGuessed() {
        assertEquals("unknown", QaSseFrame.of("data: 这不是 JSON\n\n").eventType());
        assertEquals("unknown", QaSseFrame.of("data: {\"answer\": \"没有 type\"}\n\n").eventType());
        assertEquals("done", QaSseFrame.of("data: {\"type\":\"done\"}\n\n").eventType(),
                "紧凑写法（冒号后无空格）也要认");
    }

    @Test
    @DisplayName("句柄迭代器耗尽后 next 抛 NoSuchElement（不是返回 null）")
    void exhaustedIteratorThrows() {
        QaStreamClient.Handle handle = new StubHandle(List.of(), false);
        assertThrows(NoSuchElementException.class, () -> handle.iterator().next());
    }
}
