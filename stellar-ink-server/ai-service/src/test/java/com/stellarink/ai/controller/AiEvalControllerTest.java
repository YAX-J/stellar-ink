package com.stellarink.ai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.EvalCaseResultDTO;
import com.stellarink.aiclient.dto.EvalRunRequestDTO;
import com.stellarink.aiclient.dto.EvalRunResponseDTO;
import com.stellarink.aiclient.dto.EvalStrategySummaryDTO;
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
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 评测出口的权限与转发契约。
 *
 * <p>为什么用 {@code @WebMvcTest}：这一层只做「鉴权复核 + 原样转发」，
 * 真正的评测在 Python 侧（`tests/test_eval_api.py` 覆盖）。切片测试能把
 * 「未登录一律 401」与「响应形状」测干净，不必起数据源与 Nacos。
 *
 * <p>鉴权有两种测法，都要有：
 * <ul>
 *   <li>真实路径（不打桩）：切片里没有 Sa-Token 上下文，{@code AuthHelper} 会把
 *       JWT 异常翻译成 401 —— 这正好覆盖「未登录」这条线，也验证了不是 500；</li>
 *   <li>带桩路径：用 {@code mockStatic} 指定「当前是 ADMIN」，并**显式校验控制器确实
 *       要求了 ADMIN**（而不是碰巧放行）。</li>
 * </ul>
 */
@WebMvcTest(controllers = AiEvalController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class AiEvalControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PythonAiClient pythonAiClient;

    /**
     * 模型配置控制器也在同一个切片里被装配，它要 Mapper（切片里没有库）——
     * 与 `AiHealthControllerTest` 一样 mock 掉，切片只测 Web 行为。
     */
    @MockBean
    private AiProviderConfigService aiProviderConfigService;

    private static EvalRunResponseDTO sampleResponse() {
        EvalRunResponseDTO response = new EvalRunResponseDTO();
        response.setDataset("公开文章黄金集 v1");
        response.setDatasetDescription("30 题：20 有答案 + 10 无答案");
        response.setCorpusSource("seed-sql:02-init-data.sql");
        response.setCorpusPosts(29);
        response.setCorpusChunks(41);
        response.setModels("fake");
        response.setKs(List.of(1, 3, 5, 10));
        response.setStrategies(List.of(
                EvalStrategySummaryDTO.builder().key("sparse").description("sparse, candidateK=30").build(),
                EvalStrategySummaryDTO.builder().key("hybrid").description("sparse+dense, candidateK=30").build()));
        response.setPerStrategy(Map.of(
                "sparse", Map.of("recall@1", 0.8333, "refusalRate", 0.4),
                "hybrid", Map.of("recall@1", 0.2333, "refusalRate", 0.0)));
        response.setCases(List.of(EvalCaseResultDTO.builder()
                .caseId("q001")
                .strategy("sparse")
                .question("作者为什么坚持写博客？")
                .caseType("answerable")
                .retrievedPosts(List.of(1L, 8L))
                .relevantPosts(List.of(1L, 8L, 9L))
                .refused(false)
                .latencyMs(0.3)
                .build()));
        response.setElapsedMs(49.4);
        response.setNotes(List.of("本次使用 FakeProvider 的哈希伪向量：Dense 两列只证明向量通路接对了。"));
        return response;
    }

    /** 打桩「当前是 ADMIN」，并保留 requireAtLeast 的语义（否则测试会变成「恒放行」）。 */
    private static void stubLoggedInAs(MockedStatic<AuthHelper> auth, Role role, long userId) {
        auth.when(AuthHelper::currentRole).thenReturn(role);
        auth.when(AuthHelper::loginId).thenReturn(userId);
        auth.when(() -> AuthHelper.requireAtLeast(any())).thenAnswer(invocation -> {
            Role required = invocation.getArgument(0);
            if (!role.atLeast(required)) {
                throw new com.stellarink.sharedmodel.exception.BusinessException(
                        com.stellarink.sharedmodel.enums.ErrorCode.FORBIDDEN, "权限不足，无法执行该操作。");
            }
            return null;
        });
    }

    @Test
    @DisplayName("ADMIN 拉策略清单：标准五组由 Python 定义，Java 只转发")
    void adminCanListStrategies() throws Exception {
        when(pythonAiClient.evalStrategies()).thenReturn(List.of(
                Map.of("key", "sparse", "enableSparse", true, "enableDense", false),
                Map.of("key", "hybrid+rerank", "enableSparse", true, "enableDense", true)));

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubLoggedInAs(auth, Role.ADMIN, 1L);

            mockMvc.perform(get("/ai/admin/eval/strategies"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data[0].key").value("sparse"))
                    .andExpect(jsonPath("$.data[0].enableDense").value(false))
                    .andExpect(jsonPath("$.data[1].key").value("hybrid+rerank"));

            auth.verify(() -> AuthHelper.requireAtLeast(Role.ADMIN));
        }
    }

    @Test
    @DisplayName("未登录：两个接口都拒绝，且返回 code=401 而不是 500")
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/ai/admin/eval/datasets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/ai/admin/eval/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/ai/admin/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("ADMIN 拉数据集清单：控制器确实要求 ADMIN，并原样转发 Python 的返回")
    void adminCanListDatasets() throws Exception {
        when(pythonAiClient.evalDatasets()).thenReturn(List.of(Map.of(
                "id", "golden_v1",
                "name", "公开文章黄金集 v1",
                "cases", 30,
                "answerableCases", 20,
                "unanswerableCases", 10)));

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubLoggedInAs(auth, Role.ADMIN, 1L);

            mockMvc.perform(get("/ai/admin/eval/datasets"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data[0].id").value("golden_v1"))
                    .andExpect(jsonPath("$.data[0].cases").value(30));

            auth.verify(() -> AuthHelper.requireAtLeast(Role.ADMIN));
        }
    }

    @Test
    @DisplayName("ADMIN 跑评测：对比表、逐题明细与有答案题的检索结果都在 data 里")
    void adminCanRunEvaluation() throws Exception {
        when(pythonAiClient.evalRun(any())).thenReturn(sampleResponse());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubLoggedInAs(auth, Role.ADMIN, 1L);

            MvcResult result = mockMvc.perform(post("/ai/admin/eval/run")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dataset\":\"golden_v1\",\"maxCases\":30}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.dataset").value("公开文章黄金集 v1"))
                    .andExpect(jsonPath("$.data.corpusSource").value("seed-sql:02-init-data.sql"))
                    .andExpect(jsonPath("$.data.models").value("fake"))
                    .andExpect(jsonPath("$.data.perStrategy.sparse.recall@1").value(0.8333))
                    .andExpect(jsonPath("$.data.cases[0].caseId").value("q001"))
                    .andExpect(jsonPath("$.data.cases[0].retrievedPosts[0]").value(1))
                    .andExpect(jsonPath("$.data.notes[0]").exists())
                    .andReturn();

            JsonNode data = MAPPER.readTree(bodyOf(result)).get("data");
            assertEquals("sparse", data.get("strategies").get(0).get("key").asText());
            // 诚实提示必须原样透出：面板要展示它，Java 不能吞掉
            assertTrue(data.get("notes").get(0).asText().contains("FakeProvider"));
            auth.verify(() -> AuthHelper.requireAtLeast(Role.ADMIN));
        }
    }

    @Test
    @DisplayName("非 ADMIN：403，且不会真的去调 Python")
    void nonAdminIsForbidden() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubLoggedInAs(auth, Role.AUTHOR, 9L);

            mockMvc.perform(post("/ai/admin/eval/run")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        }

        verify(pythonAiClient, org.mockito.Mockito.never()).evalRun(any());
    }

    @Test
    @DisplayName("控制器只做转发：请求体原样传给 Python，留空就是不加工")
    void requestIsForwardedVerbatim() throws Exception {
        when(pythonAiClient.evalRun(any())).thenReturn(sampleResponse());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubLoggedInAs(auth, Role.ADMIN, 1L);

            mockMvc.perform(post("/ai/admin/eval/run")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dataset\":\"golden_v1\",\"maxCases\":3}"))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<EvalRunRequestDTO> captor = ArgumentCaptor.forClass(EvalRunRequestDTO.class);
        verify(pythonAiClient).evalRun(captor.capture());
        assertEquals("golden_v1", captor.getValue().getDataset());
        assertEquals(3, captor.getValue().getMaxCases());
        assertNull(captor.getValue().getStrategies(), "留空就留空：标准五组由 Python 决定");
    }

    @Test
    @DisplayName("响应里不出现内网地址、密钥或环境变量名")
    void doesNotLeakInternals() throws Exception {
        when(pythonAiClient.evalRun(any())).thenReturn(sampleResponse());

        String body;
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubLoggedInAs(auth, Role.ADMIN, 1L);
            body = bodyOf(mockMvc.perform(post("/ai/admin/eval/run")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn());
        }

        for (String forbidden : new String[]{"8200", "127.0.0.1", "AI_INTERNAL_SECRET", "apiKey", "secret"}) {
            assertFalse(body.contains(forbidden), "响应泄露了内部信息：" + forbidden);
        }
    }

    private static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
