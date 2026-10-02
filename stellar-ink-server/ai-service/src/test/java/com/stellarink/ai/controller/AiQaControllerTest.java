package com.stellarink.ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.CitationDTO;
import com.stellarink.aiclient.dto.QaAnswerDTO;
import com.stellarink.aiclient.dto.QaStreamRequestDTO;
import com.stellarink.aiclient.dto.UsageDTO;
import com.stellarink.aiclient.enums.DoneReason;
import com.stellarink.ai.service.AiMemoryService;
import com.stellarink.ai.service.AiModelLibraryService;
import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.common.advice.GlobalExceptionHandler;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.ai.service.AiUsageService;
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

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 问答出口的权限、转发与拒答透传。
 *
 * <p>这一层不产生答案（答案在 Python），所以要盯的是三件事：
 * 未登录一律拒绝、请求体原样转发（不在这里加料）、`evidenceSufficient=false` 必须原样透给前端 ——
 * 前端靠它显示「文章里没有找到依据」，吞掉它就会把拒答渲染成空答案。
 */
@WebMvcTest(controllers = AiQaController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("unittest")
class AiQaControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiMemoryService memoryService;

    @MockBean
    private PythonAiClient pythonAiClient;

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

    /** 切片会把同包组件一起装配：模型配置控制器要 Mapper，这里 mock 掉（与其它切片测试一致）。 */
    @MockBean
    private AiProviderConfigService aiProviderConfigService;

    /** 模型库服务同样会被切片扫到，而它依赖 MyBatis Mapper：不 mock 掉，整个切片上下文就起不来。 */
    @MockBean
    private AiModelLibraryService modelLibraryService;

    /** Wiki 服务（E4-2）依赖 MyBatis Mapper：切片里不 mock 掉，整个上下文起不来。 */
    @MockBean
    private AiWikiService wikiService;

    private static QaAnswerDTO answered() {
        QaAnswerDTO answer = new QaAnswerDTO();
        answer.setAnswer("星笺把文章比作星辰，是因为每篇文章都对应夜空中的一个坐标。");
        answer.setCitations(List.of(CitationDTO.builder()
                .postId(1L)
                .title("在算法的洪流里，做一个缓慢的人")
                .chunkIndex(0)
                .snippet("每一篇文章都有固定的坐标，不会被新的噪音冲走。")
                .score(0.83)
                .build()));
        answer.setDoneReason(DoneReason.STOP);
        UsageDTO usage = new UsageDTO();
        usage.setPromptTokens(120);
        usage.setCompletionTokens(30);
        usage.setTotalTokens(150);
        usage.setLatencyMs(42);
        usage.setModel("fake");
        answer.setUsage(usage);
        answer.setEvidenceSufficient(true);
        return answer;
    }

    private static QaAnswerDTO refused() {
        QaAnswerDTO answer = new QaAnswerDTO();
        answer.setAnswer("这几篇文章里没有找到能回答这个问题的依据。");
        answer.setCitations(List.of());
        answer.setDoneReason(DoneReason.REFUSED);
        answer.setUsage(new UsageDTO());
        answer.setEvidenceSufficient(false);
        return answer;
    }

    @Test
    @DisplayName("未登录：401（问答要花算力，不能匿名）")
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/ai/qa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"作者为什么坚持写博客？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("空问题：参数校验拦下（走全局处理器的 PARAM_ERROR），不消耗一次检索")
    void blankQuestionIsRejected() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::loginId).thenReturn(5L);

            mockMvc.perform(post("/ai/qa")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"   \"}"))
                    .andExpect(status().isOk())
                    // 全局处理器把 @NotBlank 失败映射成 PARAM_ERROR（1001）+ HTTP 200，
                    // 与仓库其它服务一致；前端按 code 判定即可
                    .andExpect(jsonPath("$.code").value(1001));
        }

        verify(pythonAiClient, org.mockito.Mockito.never()).qaAsk(any());
    }

    @Test
    @DisplayName("登录读者提问：答案与引用原样返回")
    void readerCanAsk() throws Exception {
        when(pythonAiClient.qaAsk(any())).thenReturn(answered());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::loginId).thenReturn(5L);

            MvcResult result = mockMvc.perform(post("/ai/qa")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"作者为什么坚持写博客？\",\"topK\":5}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.evidenceSufficient").value(true))
                    .andExpect(jsonPath("$.data.doneReason").value("stop"))
                    .andExpect(jsonPath("$.data.citations[0].postId").value(1))
                    .andExpect(jsonPath("$.data.citations[0].title").value("在算法的洪流里，做一个缓慢的人"))
                    .andExpect(jsonPath("$.data.usage.model").value("fake"))
                    .andReturn();

            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertFalse(body.contains("8200"), "响应不该出现内网端口");
            assertFalse(body.contains("127.0.0.1"), "响应不该出现内网地址");
        }
    }

    @Test
    @DisplayName("拒答要透传：前端靠 evidenceSufficient 显示「没有依据」而不是空白")
    void refusalIsPassedThrough() throws Exception {
        when(pythonAiClient.qaAsk(any())).thenReturn(refused());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::loginId).thenReturn(5L);

            mockMvc.perform(post("/ai/qa")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"星笺支持全文检索吗？\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.evidenceSufficient").value(false))
                    .andExpect(jsonPath("$.data.doneReason").value("refused"))
                    .andExpect(jsonPath("$.data.answer").isNotEmpty())
                    .andExpect(jsonPath("$.data.citations").isEmpty());
        }
    }

    @Test
    @DisplayName("请求体原样转发：去掉首尾空白，但不加任何默认值")
    void requestIsForwardedVerbatim() throws Exception {
        when(pythonAiClient.qaAsk(any())).thenReturn(answered());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::loginId).thenReturn(5L);

            mockMvc.perform(post("/ai/qa")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"  作者为什么坚持写博客？  \"}"))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<QaStreamRequestDTO> captor = ArgumentCaptor.forClass(QaStreamRequestDTO.class);
        verify(pythonAiClient).qaAsk(captor.capture());
        assertEquals("作者为什么坚持写博客？", captor.getValue().getQuestion(), "首尾空白要去掉");
        assertEquals(null, captor.getValue().getTopK(), "没传就是没传：默认值由 Python 决定");
    }

    @Test
    @DisplayName("响应不含用户身份与请求原文（审计留在服务端日志）")
    void responseDoesNotEchoIdentity() throws Exception {
        when(pythonAiClient.qaAsk(any())).thenReturn(answered());

        String body;
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::loginId).thenReturn(5L);
            body = mockMvc.perform(post("/ai/qa")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"作者为什么坚持写博客？\"}"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
        }

        assertFalse(body.contains("userId"), "响应里不该带用户 id");
        assertFalse(body.contains("AI_INTERNAL_SECRET"));
    }

    @Test
    @DisplayName("ADMIN 也能问：门槛是「登录」，不做角色区分")
    void adminCanAskToo() throws Exception {
        when(pythonAiClient.qaAsk(any())).thenReturn(answered());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::loginId).thenReturn(1L);
            auth.when(AuthHelper::currentRole).thenReturn(Role.ADMIN);

            mockMvc.perform(post("/ai/qa")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"作者为什么坚持写博客？\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }
    }
}
