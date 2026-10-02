package com.stellarink.ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.WritingCandidateDTO;
import com.stellarink.aiclient.dto.WritingSuggestRequestDTO;
import com.stellarink.aiclient.dto.WritingSuggestResultDTO;
import com.stellarink.aiclient.dto.UsageDTO;
import com.stellarink.aiclient.enums.WritingTask;
import com.stellarink.aiclient.enums.WritingTone;
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
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Copilot 出口的权限、字面量映射与「不写正文」。
 *
 * <p>三件事必须钉住：门槛是 AUTHOR（不是登录就行）、字符串字面量到枚举的映射不能错
 * （错了会静默变成另一个任务）、以及**这一层没有任何写入路径** ——
 * 后者靠「只返回候选 + 不出现落库调用」体现，代码里根本没有 Mapper 依赖。
 */
@WebMvcTest(controllers = AiWritingController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("unittest")
class AiWritingControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DRAFT = "今晚星星很多。我坐在窗边写字。";

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

    private static WritingSuggestResultDTO suggested() {
        WritingSuggestResultDTO result = new WritingSuggestResultDTO();
        result.setTask(WritingTask.POLISH);
        result.setCandidates(List.of(
                WritingCandidateDTO.builder().text("今晚有星。").rationale("去掉程度词").build(),
                WritingCandidateDTO.builder().text("屋里有静。").build()));
        UsageDTO usage = new UsageDTO();
        usage.setPromptTokens(120);
        usage.setCompletionTokens(40);
        usage.setTotalTokens(160);
        usage.setLatencyMs(30);
        usage.setModel("fake-copilot");
        result.setUsage(usage);
        return result;
    }

    private static String body(String task, String tone) {
        return """
                {"task": "%s", "draft": "%s", "tone": "%s", "candidateCount": 2}
                """.formatted(task, DRAFT, tone);
    }

    /**
     * 打桩「当前是作者」。
     *
     * <p>`requireAtLeast` 是 void：不能用 `thenReturn`（Mockito 会直接报错），
     * 用 `thenAnswer` 返回 null 才表示「放行」。
     */
    private static void stubAsAuthor(MockedStatic<AuthHelper> auth) {
        auth.when(AuthHelper::currentRole).thenReturn(Role.AUTHOR);
        auth.when(AuthHelper::loginId).thenReturn(3L);
        auth.when(() -> AuthHelper.requireAtLeast(any())).thenAnswer(invocation -> null);
    }

    @Test
    @DisplayName("未登录：401")
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/ai/writing/suggest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("polish", "keep")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("登录但不是作者：403（草稿属于创作内容）")
    void requiresAuthorRole() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::currentRole).thenReturn(Role.READER);
            auth.when(AuthHelper::loginId).thenReturn(9L);
            auth.when(() -> AuthHelper.requireAtLeast(any())).thenThrow(
                    new com.stellarink.sharedmodel.exception.BusinessException(
                            com.stellarink.sharedmodel.enums.ErrorCode.FORBIDDEN, "权限不足，无法执行该操作。"));

            mockMvc.perform(post("/ai/writing/suggest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("polish", "keep")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        }

        verify(pythonAiClient, org.mockito.Mockito.never()).writingSuggest(any());
    }

    @Test
    @DisplayName("作者可用：候选与理由原样返回，模型标识透出")
    void authorCanSuggest() throws Exception {
        when(pythonAiClient.writingSuggest(any())).thenReturn(suggested());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsAuthor(auth);

            MvcResult result = mockMvc.perform(post("/ai/writing/suggest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("polish", "restrained")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.task").value("polish"))
                    .andExpect(jsonPath("$.data.candidates[0].text").value("今晚有星。"))
                    .andExpect(jsonPath("$.data.candidates[0].rationale").value("去掉程度词"))
                    .andExpect(jsonPath("$.data.usage.model").value("fake-copilot"))
                    .andReturn();

            String response = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertFalse(response.contains("8200"), "响应不该出现内网端口");
        }
    }

    @Test
    @DisplayName("字面量映射：小写字符串必须变成对应的枚举，别串到别的任务上")
    void literalsMapToTheRightEnums() throws Exception {
        when(pythonAiClient.writingSuggest(any())).thenReturn(suggested());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsAuthor(auth);

            mockMvc.perform(post("/ai/writing/suggest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("summary", "concise")))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<WritingSuggestRequestDTO> captor =
                ArgumentCaptor.forClass(WritingSuggestRequestDTO.class);
        verify(pythonAiClient).writingSuggest(captor.capture());
        assertEquals(WritingTask.SUMMARY, captor.getValue().getTask());
        assertEquals(WritingTone.CONCISE, captor.getValue().getTone());
        assertEquals(2, captor.getValue().getCandidateCount());
    }

    @Test
    @DisplayName("未知 task：参数校验拦下（不会走到 Python）")
    void unknownTaskIsRejected() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsAuthor(auth);

            mockMvc.perform(post("/ai/writing/suggest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("translate", "keep")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1001));
        }

        verify(pythonAiClient, org.mockito.Mockito.never()).writingSuggest(any());
    }

    @Test
    @DisplayName("空 instruction 落成 null：区分「没提要求」与「要求是空字符串」")
    void blankInstructionBecomesNull() throws Exception {
        when(pythonAiClient.writingSuggest(any())).thenReturn(suggested());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsAuthor(auth);

            mockMvc.perform(post("/ai/writing/suggest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"task": "polish", "draft": "%s", "instruction": "   "}
                                    """.formatted(DRAFT)))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<WritingSuggestRequestDTO> captor =
                ArgumentCaptor.forClass(WritingSuggestRequestDTO.class);
        verify(pythonAiClient).writingSuggest(captor.capture());
        assertNull(captor.getValue().getInstruction(), "空白要求应当落成 null");
    }

    @Test
    @DisplayName("多写的字段被忽略（Spring Boot 默认），且不会渗进内部契约")
    void unknownFieldIsIgnoredByJacksonDefault() throws Exception {
        when(pythonAiClient.writingSuggest(any())).thenReturn(suggested());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsAuthor(auth);

            // 说明：仓库里的 Java DTO 都没有打开 fail-on-unknown-properties（Spring Boot 默认关闭），
            // 严格拒绝未知字段的是 Python 侧契约（extra=forbid）。真正要保证的是：
            // 客户端多写的字段**不会**渗进内部 DTO —— 内部契约里根本不存在那个字段。
            mockMvc.perform(post("/ai/writing/suggest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"task": "polish", "draft": "%s", "autoApply": true}
                                    """.formatted(DRAFT)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }

        ArgumentCaptor<WritingSuggestRequestDTO> captor =
                ArgumentCaptor.forClass(WritingSuggestRequestDTO.class);
        verify(pythonAiClient).writingSuggest(captor.capture());
        assertEquals(WritingTask.POLISH, captor.getValue().getTask());
        assertEquals(DRAFT, captor.getValue().getDraft());
    }
}
