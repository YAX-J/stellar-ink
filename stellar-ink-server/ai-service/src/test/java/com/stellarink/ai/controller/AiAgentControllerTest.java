package com.stellarink.ai.controller;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.AgentAskRequestDTO;
import com.stellarink.aiclient.dto.AgentAskResultDTO;
import com.stellarink.aiclient.dto.AgentStepDTO;
import com.stellarink.aiclient.dto.CitationDTO;
import com.stellarink.ai.service.AiModelLibraryService;
import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.common.advice.GlobalExceptionHandler;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.enums.Role;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent 出口的权限、预算收紧与「预算触顶不是失败」。
 *
 * <p>三条必须钉住：门槛是登录、预算**只能被客户端收紧**、
 * 以及 `doneReason=length` 与空答案要**原样透传** ——
 * 在这层「顺手把空答案当失败」会让前端再也看不到「查到这些但没收敛」。
 */
@WebMvcTest(controllers = AiAgentController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class AiAgentControllerTest {

    private static final String QUESTION = "一年写十八万字的方法是什么？";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PythonAiClient pythonAiClient;

    /** 切片会把同包组件一起装配：模型配置控制器要 Mapper，这里 mock 掉。 */
    @MockBean
    private AiProviderConfigService aiProviderConfigService;

    /** 模型库服务同样会被切片扫到，而它依赖 MyBatis Mapper：不 mock 掉，整个切片上下文就起不来。 */
    @MockBean
    private AiModelLibraryService modelLibraryService;

    private static AgentAskResultDTO budgetExhausted() {
        AgentAskResultDTO result = new AgentAskResultDTO();
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
                    .andExpect(jsonPath("$.data.doneReason").value("length"))
                    .andExpect(jsonPath("$.data.answer").value(""))
                    .andExpect(jsonPath("$.data.citations[0].postId").value(1))
                    .andExpect(jsonPath("$.data.interruptedBy").value("budget"));
        }
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
}
