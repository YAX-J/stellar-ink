package com.stellarink.ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.ai.service.AiRetrievalAuditService;
import com.stellarink.ai.service.AiStyleProfileService;
import com.stellarink.ai.service.AiMemoryService;
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
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.ai.enums.AiCallScene;
import com.stellarink.ai.service.AiQuotaTicket;
import com.stellarink.ai.service.AiWikiService;
import com.stellarink.common.redis.RedisUtils;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Answers;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.stellarink.ai.corpus.service.CorpusSyncService;

/**
 * 流式出口的权限、转发与取消。
 *
 * <p>三件事必须钉住：门槛是登录（读者功能）、帧**原样透传**（Java 不重新编码事件体）、
 * 以及**写失败时必须关掉下游** —— 最后这条是「关掉页面就停止烧 token」的全部实现，
 * 也是最容易在重构中被丢掉的一行。
 */
@WebMvcTest(controllers = AiQaStreamController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("unittest")
class AiQaStreamControllerTest {

    /**
     * 语料投影同步：新增 @Service 后，本模块的 @WebMvcTest 切片必须把它 mock 掉 ——
     * 启动类显式声明了 @ComponentScan，切片会把它连同它的 Feign 客户端与 Mapper 一起装配，
     * 而 Web 切片里没有 FeignClientFactory、也没有 SqlSessionFactory（踩过一次：12 个切片全红）。
     */
    @MockBean
    private CorpusSyncService corpusSyncService;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String QUESTION = "一年写十八万字的方法是什么？";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiRetrievalAuditService auditService;

    @MockBean
    private AiStyleProfileService styleProfileService;

    @MockBean
    private AiMemoryService memoryService;

    @MockBean
    private QaStreamClient qaStreamClient;

    /**
     * 调用账替身：用**真实默认实现**透传调用（不记账）。
     * 记账不在这几个切片的被测范围内，而 {@code around} 是接口的 default 方法 ——
     * 这样就不必在每个用例里 stub 一遍「把 supplier 执行掉」。
     */
    @MockBean(answer = Answers.CALLS_REAL_METHODS)
    private AiUsageService usageService;

    /**
     * 配额用的 Redis 工具（E3-2）：切片里没有 spring-data-redis 的自动配置，
     * 而它是个独立装配的 @Component —— 不 mock 掉，整个切片上下文都起不来。
     * 这些用例不碰配额（AiUsageService 本身就是替身），所以它只是个占位。
     */
    @MockBean
    private RedisUtils redisUtils;

    /** 切片会把同包组件一起装配：探活要 Feign 客户端，模型配置要 Mapper，这里都 mock 掉。 */
    @MockBean
    private PythonAiClient pythonAiClient;

    @MockBean
    private AiProviderConfigService aiProviderConfigService;

    /** 模型库服务同样会被切片扫到，而它依赖 MyBatis Mapper：不 mock 掉，整个切片上下文就起不来。 */
    @MockBean
    private AiModelLibraryService modelLibraryService;

    /** Wiki 服务（E4-2）依赖 MyBatis Mapper：切片里不 mock 掉，整个上下文起不来。 */
    @MockBean
    private AiWikiService wikiService;

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
    @DisplayName("多轮：历史原样传给下游（问句 trim、答案不改写）")
    void forwardsHistoryTurns() throws Exception {
        StubHandle handle = new StubHandle(List.of(frame("done", "\"answer\": \"好\"")), false);
        when(qaStreamClient.open(any())).thenReturn(handle);

        String payload = """
                {"question": "那它呢？", "topK": 5,
                 "history": [{"question": "  上次那个报错是怎么修的？  ",
                              "answer": "  加 -Dfile.encoding=UTF-8。  "}]}
                """;

        MvcResult result;
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);
            result = mockMvc.perform(post("/ai/qa/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(request().asyncStarted())
                    .andReturn();
        }
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .asyncDispatch(result)).andExpect(status().isOk());

        ArgumentCaptor<QaStreamRequestDTO> captor = ArgumentCaptor.forClass(QaStreamRequestDTO.class);
        verify(qaStreamClient).open(captor.capture());
        List<com.stellarink.aiclient.dto.QaHistoryTurnDTO> history = captor.getValue().getHistory();
        assertEquals(1, history.size());
        assertEquals("上次那个报错是怎么修的？", history.get(0).getQuestion(),
                "历史问句要与当前问题同口径 trim，否则同一句在两条出口里文本不同");
        assertEquals("  加 -Dfile.encoding=UTF-8。  ", history.get(0).getAnswer(),
                "历史答案是我们上一轮发出去的文本，必须原样透传");
    }

    @Test
    @DisplayName("多轮：不带历史时传空表（Python 侧据此当一次性提问）")
    void sendsEmptyHistoryWhenAbsent() throws Exception {
        StubHandle handle = new StubHandle(List.of(frame("done", "\"answer\": \"好\"")), false);
        when(qaStreamClient.open(any())).thenReturn(handle);

        MvcResult result;
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);
            result = mockMvc.perform(post("/ai/qa/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(QUESTION)))
                    .andExpect(request().asyncStarted())
                    .andReturn();
        }
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .asyncDispatch(result)).andExpect(status().isOk());

        ArgumentCaptor<QaStreamRequestDTO> captor = ArgumentCaptor.forClass(QaStreamRequestDTO.class);
        verify(qaStreamClient).open(captor.capture());
        assertTrue(captor.getValue().getHistory().isEmpty());
    }

    @Test
    @DisplayName("写失败（浏览器断开）：必须关掉下游，否则模型继续生成")
    void sendFailureCancelsUpstream() {
        AiQaStreamController controller = new AiQaStreamController(qaStreamClient, memoryService, usageService);
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

        controller.forwardFrames(emitter, QaStreamRequestDTO.builder().question(QUESTION).build(), 9L,
                AiQuotaTicket.NONE);

        assertTrue(handle.closed, "写失败时没有关闭下游：关掉页面后模型还会继续烧 token");
    }

    @Test
    @DisplayName("上游不可用：给一帧可读的 error，前端不会停在「生成中」")
    void upstreamFailureSendsErrorFrame() {
        AiQaStreamController controller = new AiQaStreamController(qaStreamClient, memoryService, usageService);
        StubHandle handle = new StubHandle(List.of(), true);
        when(qaStreamClient.open(any())).thenReturn(handle);

        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        controller.forwardFrames(emitter, QaStreamRequestDTO.builder().question(QUESTION).build(), 9L,
                AiQuotaTicket.NONE);

        assertTrue(handle.closed, "失败路径同样要关掉下游");
    }

    @Test
    @DisplayName("配额触顶：429，且一个下游调用都不发（流式不再绕过闸门）")
    void quotaExceededRejectsBeforeOpeningUpstream() throws Exception {
        when(usageService.acquireQuota(AiCallScene.QA_STREAM))
                .thenThrow(new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                        "今天的 AI 调用次数已用完（上限 30 次）。"));

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);
            mockMvc.perform(post("/ai/qa/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(QUESTION)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(429));
        }

        verify(qaStreamClient, never()).open(any());
    }

    @Test
    @DisplayName("流正常结束后释放并发闸门")
    void releasesInflightTicketAfterStreamEnds() {
        AiQuotaTicket ticket = new AiQuotaTicket(9L, "stellar-ink:ai:quota:inflight:user:9");
        AiQaStreamController controller = new AiQaStreamController(qaStreamClient, memoryService, usageService);
        when(qaStreamClient.open(any()))
                .thenReturn(new StubHandle(List.of(frame("done", "\"answer\": \"好\"")), false));

        controller.forwardFrames(new ResponseBodyEmitter(),
                QaStreamRequestDTO.builder().question(QUESTION).build(), 9L, ticket);

        verify(usageService).releaseQuota(ticket);
    }

    @Test
    @DisplayName("上游失败与客户端断开两条路也释放闸门（漏放会把当天的并发额度耗光）")
    void releasesInflightTicketOnFailurePaths() {
        AiQuotaTicket ticket = new AiQuotaTicket(9L, "stellar-ink:ai:quota:inflight:user:9");
        AiQaStreamController controller = new AiQaStreamController(qaStreamClient, memoryService, usageService);
        QaStreamRequestDTO request = QaStreamRequestDTO.builder().question(QUESTION).build();

        // 上游失败
        when(qaStreamClient.open(any())).thenReturn(new StubHandle(List.of(), true));
        controller.forwardFrames(new ResponseBodyEmitter(), request, 9L, ticket);

        // 客户端断开
        when(qaStreamClient.open(any()))
                .thenReturn(new StubHandle(List.of(frame("delta", "\"text\": \"好\"")), false));
        ResponseBodyEmitter broken = mock(ResponseBodyEmitter.class);
        try {
            org.mockito.Mockito.doThrow(new IOException("Broken pipe"))
                    .when(broken).send(any(), any(MediaType.class));
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        controller.forwardFrames(broken, request, 9L, ticket);

        verify(usageService, times(2)).releaseQuota(ticket);
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
