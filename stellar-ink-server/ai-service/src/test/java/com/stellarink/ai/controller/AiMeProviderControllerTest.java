package com.stellarink.ai.controller;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.ProviderModelDTO;
import com.stellarink.aiclient.dto.ProviderModelsRequestDTO;
import com.stellarink.aiclient.dto.ProviderModelsResultDTO;
import com.stellarink.ai.corpus.service.CorpusSyncService;
import com.stellarink.ai.service.AiMemoryService;
import com.stellarink.ai.service.AiModelLibraryService;
import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.ai.service.AiRetrievalAuditService;
import com.stellarink.ai.service.AiStyleProfileService;
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.ai.service.AiWikiService;
import com.stellarink.common.advice.GlobalExceptionHandler;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.redis.RedisUtils;
import com.stellarink.sharedmodel.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
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
 * 拉取模型清单出口（{@code POST /ai/me/providers/models}）的门槛、转发与两条红线。
 *
 * <p>要钉住的东西：
 * <ol>
 *   <li><b>门槛是登录</b>：它只是「帮我问一下这家供应商有哪些模型」，不比保存我的配置多任何权限；
 *       未登录一律 401，且**不碰下游**（下游要拿用户的密钥去打外部地址）；</li>
 *   <li><b>清单与 source 原样透传</b>：Java 不加工、不排序、不裁剪 ——
 *       裁一次就会与 Python 的 `truncated` 对不上；</li>
 *   <li><b>不进调用账、不占配额</b>：拉清单零模型调用（与 {@code /ai/agent/verify} 同一口径）。
 *       记一笔零成本的调用会让成本看板上的「模型花了多少」失真；</li>
 *   <li><b>明文密钥不进拒绝响应</b>：越权/参数错误的响应体不得回显请求体里的 Key。</li>
 * </ol>
 */
@WebMvcTest(controllers = AiMeProviderController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("unittest")
class AiMeProviderControllerTest {

    /** 明文密钥：它只在**请求体**里出现一次，任何响应与日志里都不该有它。 */
    private static final String PLAINTEXT_KEY = "sk-plaintext-model-list-key";

    private static final String BASE_URL = "https://api.example.com/v1";

    /**
     * 语料投影同步：切片会装配同包组件，而它带着 Feign 客户端与 Mapper ——
     * 不 mock 掉，整个切片上下文起不来（踩过一次：12 个切片全红）。
     */
    @MockBean
    private CorpusSyncService corpusSyncService;

    @MockBean
    private AiRetrievalAuditService auditService;

    @MockBean
    private AiStyleProfileService styleProfileService;

    @MockBean
    private AiMemoryService memoryService;

    @MockBean
    private AiWikiService wikiService;

    @MockBean
    private AiModelLibraryService modelLibraryService;

    /** 本控制器依赖它（列表/保存/删除/自检），切片里必须给替身 */
    @MockBean
    private AiProviderConfigService providerConfigService;

    @MockBean
    private PythonAiClient pythonAiClient;

    /**
     * 调用账替身：用**真实默认实现**透传（不记账）。
     * 本用例要断言的正是「它一次都没被调用过」—— 记账逻辑不在这里的验证范围。
     */
    @MockBean(answer = Answers.CALLS_REAL_METHODS)
    private AiUsageService usageService;

    /** 配额用的 Redis 工具：切片里没有 spring-data-redis 的自动配置，不 mock 掉上下文起不来 */
    @MockBean
    private RedisUtils redisUtils;

    @Autowired
    private MockMvc mockMvc;

    private static ProviderModelsResultDTO listing() {
        ProviderModelsResultDTO result = new ProviderModelsResultDTO();
        result.setModels(List.of(
                ProviderModelDTO.builder().id("m-1").created(1730000000L).build(),
                ProviderModelDTO.builder().id("m-2").build()));
        result.setTruncated(false);
        result.setSource(BASE_URL);
        return result;
    }

    private static void stubAsReader(MockedStatic<AuthHelper> auth) {
        auth.when(AuthHelper::currentRole).thenReturn(Role.READER);
        auth.when(AuthHelper::loginId).thenReturn(5L);
    }

    private static String body(String provider, String baseUrl, String apiKey, String role) {
        return """
                {"provider": %s, "baseUrl": %s, "apiKey": %s, "role": %s}
                """.formatted(
                quote(provider), quote(baseUrl), quote(apiKey), quote(role));
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    @Test
    @DisplayName("未登录：401，且**不碰下游**（下游会拿着密钥去打外部地址）")
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/ai/me/providers/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("openai_compatible", BASE_URL, PLAINTEXT_KEY, "chat")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        verify(pythonAiClient, never()).providerModels(any());
    }

    @Test
    @DisplayName("转发：清单、truncated、source 原样透传（Java 不排序、不裁剪、不加工）")
    void forwardsTheListingUntouched() throws Exception {
        when(pythonAiClient.providerModels(any())).thenReturn(listing());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/me/providers/models")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("openai_compatible", BASE_URL, null, "chat")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.models[0].id").value("m-1"))
                    .andExpect(jsonPath("$.data.models[1].id").value("m-2"))
                    .andExpect(jsonPath("$.data.truncated").value(false))
                    // source 让用户核对「我填的地址对不对」，必须原样到用户面前
                    .andExpect(jsonPath("$.data.source").value(BASE_URL));
        }
    }

    @Test
    @DisplayName("**不进调用账、不占配额**（零模型调用，与 /ai/agent/verify 同一口径）")
    void isNotRecordedAsAModelCall() throws Exception {
        when(pythonAiClient.providerModels(any())).thenReturn(listing());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/me/providers/models")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("openai_compatible", BASE_URL, PLAINTEXT_KEY, "chat")))
                    .andExpect(status().isOk());
        }

        verify(usageService, never()).around(any(), any(), any());
        verify(usageService, never()).acquireQuota(any());
    }

    @Test
    @DisplayName("参数原样转发：密钥、地址、角色都不在 Java 侧被改写")
    void forwardsTheParametersUntouched() throws Exception {
        when(pythonAiClient.providerModels(any())).thenReturn(listing());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/me/providers/models")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("openai_compatible", BASE_URL, PLAINTEXT_KEY, "reasoning")))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<ProviderModelsRequestDTO> captor =
                ArgumentCaptor.forClass(ProviderModelsRequestDTO.class);
        verify(pythonAiClient).providerModels(captor.capture());
        assertEquals(PLAINTEXT_KEY, captor.getValue().getApiKey(), "明文只在这次转发里出现一次");
        assertEquals(BASE_URL, captor.getValue().getBaseUrl());
        assertEquals("reasoning", captor.getValue().getRole(), "角色按小写键转发（Python 认这个）");
    }

    @Test
    @DisplayName("缺省值：role 不给就是 chat，provider 不给就是 openai_compatible")
    void defaultsMatchTheContract() throws Exception {
        when(pythonAiClient.providerModels(any())).thenReturn(listing());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/me/providers/models")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"baseUrl\":\"" + BASE_URL + "\",\"apiKey\":\""
                                    + PLAINTEXT_KEY + "\"}"))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<ProviderModelsRequestDTO> captor =
                ArgumentCaptor.forClass(ProviderModelsRequestDTO.class);
        verify(pythonAiClient).providerModels(captor.capture());
        assertEquals("chat", captor.getValue().getRole());
        assertEquals("openai_compatible", captor.getValue().getProvider());
    }

    @Test
    @DisplayName("空白 apiKey 归一成 null：契约里只该有一种「没给」")
    void blankKeyBecomesNull() throws Exception {
        when(pythonAiClient.providerModels(any())).thenReturn(listing());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/me/providers/models")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("openai_compatible", BASE_URL, "   ", "chat")))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<ProviderModelsRequestDTO> captor =
                ArgumentCaptor.forClass(ProviderModelsRequestDTO.class);
        verify(pythonAiClient).providerModels(captor.capture());
        assertNull(captor.getValue().getApiKey(), "空白密钥按「没给」处理，由 Python 用已存的那把");
    }

    @Test
    @DisplayName("fake 协议不需要地址：地址栏为空也照样转发（它不发任何出站请求）")
    void fakeNeedsNoBaseUrl() throws Exception {
        when(pythonAiClient.providerModels(any())).thenReturn(listing());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/me/providers/models")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("fake", null, null, "chat")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }

        ArgumentCaptor<ProviderModelsRequestDTO> captor =
                ArgumentCaptor.forClass(ProviderModelsRequestDTO.class);
        verify(pythonAiClient).providerModels(captor.capture());
        assertEquals("fake", captor.getValue().getProvider());
        assertNull(captor.getValue().getBaseUrl());
        assertNull(captor.getValue().getApiKey(), "fake 不需要密钥");
    }

    @Test
    @DisplayName("未知角色：参数错误，且不白跑一次外部请求")
    void unknownRoleIsRejected() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/me/providers/models")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("openai_compatible", BASE_URL, PLAINTEXT_KEY, "nope")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1001));
        }

        verify(pythonAiClient, never()).providerModels(any());
    }

    @Test
    @DisplayName("未知 provider：参数错误（消息里列出可选值）")
    void unknownProviderIsRejected() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            MvcResult result = mockMvc.perform(post("/ai/me/providers/models")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("anthropic", BASE_URL, PLAINTEXT_KEY, "chat")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1001))
                    .andReturn();

            String response = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(response.contains("openai_compatible") && response.contains("fake"),
                    "未知协议要列出可选值：" + response);
            assertFalse(response.contains(PLAINTEXT_KEY), "拒绝响应不该回显请求体里的密钥");
        }

        verify(pythonAiClient, never()).providerModels(any());
    }

    @Test
    @DisplayName("内网地址：参数错误（防 SSRF，与保存我的配置同一档）")
    void privateBaseUrlIsRejected() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            MvcResult result = mockMvc.perform(post("/ai/me/providers/models")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("openai_compatible", "http://127.0.0.1:8000/v1",
                                    PLAINTEXT_KEY, "chat")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1001))
                    .andReturn();

            String response = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertFalse(response.contains(PLAINTEXT_KEY), "拒绝响应不该回显请求体里的密钥");
        }

        verify(pythonAiClient, never()).providerModels(any());
    }

    @Test
    @DisplayName("地址为空：参数错误，且不白跑一次外部请求")
    void blankBaseUrlIsRejected() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/me/providers/models")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("openai_compatible", null, PLAINTEXT_KEY, "chat")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1001));
        }

        verify(pythonAiClient, never()).providerModels(any());
    }

    @Test
    @DisplayName("下游返回 null 时不抛 NPE（审计要兜得住）")
    void nullResultIsTolerated() throws Exception {
        when(pythonAiClient.providerModels(any())).thenReturn(null);

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsReader(auth);

            mockMvc.perform(post("/ai/me/providers/models")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("openai_compatible", BASE_URL, PLAINTEXT_KEY, "chat")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }
    }
}
