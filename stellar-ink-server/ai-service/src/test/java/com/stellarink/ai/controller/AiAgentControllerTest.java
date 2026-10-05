package com.stellarink.ai.controller;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.AgentAskRequestDTO;
import com.stellarink.aiclient.dto.AgentAskResultDTO;
import com.stellarink.aiclient.dto.AgentStepDTO;
import com.stellarink.aiclient.dto.AgentVerifyProblemDTO;
import com.stellarink.aiclient.dto.AgentVerifyRequestDTO;
import com.stellarink.aiclient.dto.AgentVerifyResultDTO;
import com.stellarink.aiclient.dto.CitationDTO;
import com.stellarink.ai.service.AiRetrievalAuditService;
import com.stellarink.ai.service.AiStyleProfileService;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.stellarink.ai.corpus.service.CorpusSyncService;

/**
 * Agent 出口的权限、预算收紧与「预算触顶不是失败」；A2 起还有引用核验的透传与「不记账」。
 *
 * <p>三条必须钉住：门槛是登录、预算**只能被客户端收紧**、
 * 以及 `doneReason=length` 与空答案要**原样透传** ——
 * 在这层「顺手把空答案当失败」会让前端再也看不到「查到这些但没收敛」。
 *
 * <p>核验多一条：它**零模型调用**，因此不该进调用账、也不该被配额拦下 ——
 * 记一笔零成本的调用会让成本看板上的「agent」不再是「模型花了多少」。
 */
@WebMvcTest(controllers = AiAgentController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("unittest")
class AiAgentControllerTest {

    /**
     * 语料投影同步：新增 @Service 后，本模块的 @WebMvcTest 切片必须把它 mock 掉 ——
     * 启动类显式声明了 @ComponentScan，切片会把它连同它的 Feign 客户端与 Mapper 一起装配，
     * 而 Web 切片里没有 FeignClientFactory、也没有 SqlSessionFactory（踩过一次：12 个切片全红）。
     */
    @MockBean
    private CorpusSyncService corpusSyncService;

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

    /** 切片会把同包组件一起装配：模型配置控制器要 Mapper，这里 mock 掉。 */
    @MockBean
    private AiProviderConfigService aiProviderConfigService;

    /** 模型库服务同样会被切片扫到，而它依赖 MyBatis Mapper：不 mock 掉，整个切片上下文就起不来。 */
    @MockBean
    private AiModelLibraryService modelLibraryService;

    /** Wiki 服务（E4-2）依赖 MyBatis Mapper：切片里不 mock 掉，整个上下文起不来。 */
    @MockBean
    private AiWikiService wikiService;

    private static AgentAskResultDTO budgetExhausted() {
        AgentAskResultDTO result = new AgentAskResultDTO();
        result.setAgent("searcher");
        result.setAnswer("");
        result.setDoneReason("length");
        result.setInterruptedBy("budget");
        result.setToolCalls(1);
        result.setUsageModel("fake");
        result.setLatencyMs(42L);
        CitationDTO citation = new CitationDTO();
        citation.setPostId(1L);
        citation.setTitle("写作的复利");
        citation.setChunkIndex(0);
        citation.setSnippet("一年写十八万字靠的是每天五百字。");
        result.setCitations(List.of(citation));
        AgentStepDTO step = new AgentStepDTO();
        step.setIndex(0);
        step.setTool("search_posts");
        step.setLabel("检索到 1 段");
        result.setSteps(List.of(step));
        return result;
    }

    private static void stubAsReader(MockedStatic<AuthHelper> auth) {
        auth.when(AuthHelper::currentRole).thenReturn(Role.READER);
        auth.when(AuthHelper::loginId).thenReturn(5L);
    }

    @Test
    @DisplayName("未登录：401，且不碰下游（Agent 会跑多次模型与检索）")
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/ai/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        verify(pythonAiClient, never()).agentAsk(any());
    }

    @Test
    @DisplayName("读者可用：Agent 查的仍是站内已发布文章，不比问答多任何权限")
    void readerCanAsk() throws Exception {
        when(pythonAiClient.agentAsk(any())).thenReturn(budgetExhausted());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/agent/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"" + QUESTION + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    // 预算触顶的形态必须原样透传
                    .andExpect(jsonPath("$.data.agent").value("searcher"))
                    .andExpect(jsonPath("$.data.doneReason").value("length"))
                    .andExpect(jsonPath("$.data.answer").value(""))
                    .andExpect(jsonPath("$.data.citations[0].postId").value(1))
                    .andExpect(jsonPath("$.data.interruptedBy").value("budget"));
        }
    }

    @Test
    @DisplayName("司职透传：客户端点名哪个司职就传给 Python 哪个（Java 不维护白名单）")
    void agentNameIsPassedThrough() throws Exception {
        when(pythonAiClient.agentAsk(any())).thenReturn(budgetExhausted());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/agent/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"" + QUESTION + "\",\"agent\":\"answerer\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.agent").value("searcher"));
        }

        ArgumentCaptor<AgentAskRequestDTO> captor = ArgumentCaptor.forClass(AgentAskRequestDTO.class);
        verify(pythonAiClient).agentAsk(captor.capture());
        assertEquals(
                "answerer",
                captor.getValue().getAgent(),
                "司职必须原样透传：白名单在 Python 的注册表里，Java 抄一份迟早分叉");
    }

    @Test
    @DisplayName("司职留空：传 null 而不是空串（Python 对两者都取缺省，但契约里只该有一种「没给」）")
    void blankAgentBecomesNull() throws Exception {
        when(pythonAiClient.agentAsk(any())).thenReturn(budgetExhausted());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/agent/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"" + QUESTION + "\",\"agent\":\"   \"}"))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<AgentAskRequestDTO> captor = ArgumentCaptor.forClass(AgentAskRequestDTO.class);
        verify(pythonAiClient).agentAsk(captor.capture());
        assertNull(captor.getValue().getAgent(), "空白司职按「没给」处理（由 Python 取缺省）");
    }

    @Test
    @DisplayName("预算只能收紧：客户端传大值会被压到服务端默认")
    void budgetCanOnlyBeTightened() throws Exception {
        when(pythonAiClient.agentAsk(any())).thenReturn(budgetExhausted());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/agent/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"" + QUESTION + "\",\"maxSteps\":8,\"maxToolCalls\":12}"))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<AgentAskRequestDTO> captor = ArgumentCaptor.forClass(AgentAskRequestDTO.class);
        verify(pythonAiClient).agentAsk(captor.capture());
        assertEquals(
                AiAgentController.DEFAULT_MAX_STEPS,
                captor.getValue().getMaxSteps(),
                "客户端不能把预算放宽到服务端默认之上");
        assertEquals(AiAgentController.DEFAULT_MAX_TOOL_CALLS, captor.getValue().getMaxToolCalls());
    }

    @Test
    @DisplayName("收紧有效：客户端传更小的预算要照传")
    void tighterBudgetIsHonoured() throws Exception {
        when(pythonAiClient.agentAsk(any())).thenReturn(budgetExhausted());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/agent/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"" + QUESTION + "\",\"maxSteps\":1,\"maxToolCalls\":2}"))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<AgentAskRequestDTO> captor = ArgumentCaptor.forClass(AgentAskRequestDTO.class);
        verify(pythonAiClient).agentAsk(captor.capture());
        assertEquals(1, captor.getValue().getMaxSteps());
        assertEquals(2, captor.getValue().getMaxToolCalls());
    }

    @Test
    @DisplayName("问题为空：契约层拦下（不会白跑一轮 Agent）")
    void blankQuestionIsRejected() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/agent/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"   \"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1001));
        }

        verify(pythonAiClient, never()).agentAsk(any());
    }

    @Test
    @DisplayName("预算越界：契约层拦下")
    void outOfRangeBudgetIsRejected() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/agent/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"" + QUESTION + "\",\"maxSteps\":99}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1001));
        }

        verify(pythonAiClient, never()).agentAsk(any());
    }

    @Test
    @DisplayName("级联：上游返回 null 时不抛 NPE（审计要能兜住）")
    void nullResultIsTolerated() throws Exception {
        when(pythonAiClient.agentAsk(any())).thenReturn(null);

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/agent/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"" + QUESTION + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }

        assertTrue(true, "走到这里就说明审计日志没有因为 null 而炸");
    }

    // --------------------------------------------------------------- 引用核验（A2）

    private static AgentVerifyResultDTO warnReport() {
        AgentVerifyProblemDTO problem = AgentVerifyProblemDTO.builder()
                .kind("snippetNotFound")
                .message("第 1 条引用的片段在它标注的原文里找不到 —— 这条引用没有被观察到。")
                .build();
        AgentVerifyResultDTO result = new AgentVerifyResultDTO();
        result.setVerdict("warn");
        result.setChecked(1);
        result.setEvidenceAvailable(true);
        result.setCitedIndexes(List.of(1));
        result.setOutOfRange(List.of());
        result.setUncited(false);
        result.setProblems(List.of(problem));
        return result;
    }

    private static String verifyBody(String answer) {
        return "{\"answer\":\"" + answer + "\",\"citations\":["
                + "{\"postId\":1,\"title\":\"写作的复利\",\"chunkIndex\":0,"
                + "\"snippet\":\"每天写五百字。\"}]}";
    }

    @Test
    @DisplayName("核验：未登录 401，且不碰下游")
    void verifyRequiresLogin() throws Exception {
        mockMvc.perform(post("/ai/agent/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody("看 [1]。")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        verify(pythonAiClient, never()).agentVerify(any());
    }

    @Test
    @DisplayName("核验：结论原样透传（verdict/checked/problems 一个都不改）")
    void verifyPassesTheReportThrough() throws Exception {
        when(pythonAiClient.agentVerify(any())).thenReturn(warnReport());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/agent/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(verifyBody("看 [1]。")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.verdict").value("warn"))
                    .andExpect(jsonPath("$.data.checked").value(1))
                    .andExpect(jsonPath("$.data.evidenceAvailable").value(true))
                    // ok 只表示「没查出问题」：checked 必须一起传下去，否则前端没法说清核对了几条
                    .andExpect(jsonPath("$.data.problems[0].kind").value("snippetNotFound"))
                    .andExpect(jsonPath("$.data.problems[0].message").exists());
        }
    }

    @Test
    @DisplayName("核验：答案与引用原样传给 Python（Java 不重排、不省略引用）")
    void verifyForwardsAnswerAndCitations() throws Exception {
        when(pythonAiClient.agentVerify(any())).thenReturn(warnReport());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/agent/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(verifyBody("看 [1]。")))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<AgentVerifyRequestDTO> captor =
                ArgumentCaptor.forClass(AgentVerifyRequestDTO.class);
        verify(pythonAiClient).agentVerify(captor.capture());
        assertEquals("看 [1]。", captor.getValue().getAnswer());
        assertEquals(1, captor.getValue().getCitations().size());
        assertEquals(1L, captor.getValue().getCitations().get(0).getPostId());
    }

    @Test
    @DisplayName("核验：引用为空在契约层拦下（没有输入的核验会返回一个会被误读成「核过了」的 ok）")
    void verifyRejectsEmptyCitations() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/agent/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"answer\":\"看 [1]。\",\"citations\":[]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1001));
        }

        verify(pythonAiClient, never()).agentVerify(any());
    }

    @Test
    @DisplayName("核验：**不进调用账**（它零模型调用，记一笔会让成本看板失真）")
    void verifyIsNotRecordedAsAModelCall() throws Exception {
        when(pythonAiClient.agentVerify(any())).thenReturn(warnReport());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/agent/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(verifyBody("看 [1]。")))
                    .andExpect(status().isOk());
        }

        // 替身本身就是「真实默认实现透传」，所以这里断言的是**没被调用过**
        verify(usageService, never()).around(any(), any(), any());
        verify(usageService, never()).acquireQuota(any());
    }
}
