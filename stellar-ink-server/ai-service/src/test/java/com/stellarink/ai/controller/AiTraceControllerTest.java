package com.stellarink.ai.controller;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.AiTraceDTO;
import com.stellarink.ai.service.AiRetrievalAuditService;
import com.stellarink.ai.service.AiStyleProfileService;
import com.stellarink.ai.service.AiMemoryService;
import com.stellarink.ai.service.AiModelLibraryService;
import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.ai.service.AiWikiService;
import com.stellarink.common.advice.GlobalExceptionHandler;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.redis.RedisUtils;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.vo.ai.AiTraceCallVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 链路回放出口：门槛、合并，以及**Python 挂了也要把 Java 那一半交出来**。
 *
 * <p>最后一条是这个类存在的理由：一次下游故障不该把「本来就在库里的调用账」也藏起来，
 * 那会让人以为「这次调用根本没发生」。本项目已经吃过一次同形状的亏
 * （写成功之后的刷新失败被当成写失败）。
 */
@WebMvcTest(controllers = AiTraceController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("unittest")
class AiTraceControllerTest {

    private static final String TRACE = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiRetrievalAuditService auditService;

    @MockBean
    private AiStyleProfileService styleProfileService;

    @MockBean
    private AiMemoryService memoryService;

    @MockBean
    private AiUsageService usageService;

    @MockBean
    private PythonAiClient pythonAiClient;

    /** 启动类显式声明了 @ComponentScan，切片会把别的组件一起装配，它们的依赖要 mock 掉 */
    @MockBean
    private RedisUtils redisUtils;

    @MockBean
    private AiProviderConfigService aiProviderConfigService;

    @MockBean
    private AiModelLibraryService modelLibraryService;

    /** Wiki 服务（E4-2）依赖 MyBatis Mapper：切片里不 mock 掉，整个上下文起不来。 */
    @MockBean
    private AiWikiService wikiService;

    private static AiTraceCallVO call() {
        return AiTraceCallVO.builder()
                .id(9L)
                .scene("qa")
                .providerRole("chat")
                .model("deepseek-flash")
                .userId(7L)
                .role("READER")
                .totalTokens(120)
                .latencyMs(702)
                .success(1)
                .build();
    }

    private static void stubAsAdmin(MockedStatic<AuthHelper> auth) {
        auth.when(AuthHelper::currentRole).thenReturn(Role.ADMIN);
        auth.when(() -> AuthHelper.requireAtLeast(Role.ADMIN)).thenAnswer(invocation -> null);
    }

    @Test
    @DisplayName("未登录：401，且不查账、不碰 Python")
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/ai/admin/trace/{traceId}", TRACE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        verify(usageService, never()).traceCalls(anyString());
        verify(pythonAiClient, never()).traceReplay(anyString());
    }

    @Test
    @DisplayName("读者：403（账里有「谁在什么时候用了多少」）")
    void requiresAdmin() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::currentRole).thenReturn(Role.READER);
            auth.when(() -> AuthHelper.requireAtLeast(Role.ADMIN)).thenThrow(
                    new com.stellarink.sharedmodel.exception.BusinessException(
                            com.stellarink.sharedmodel.enums.ErrorCode.FORBIDDEN, "权限不足"));

            mockMvc.perform(get("/ai/admin/trace/{traceId}", TRACE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        }

        verify(pythonAiClient, never()).traceReplay(anyString());
    }

    @Test
    @DisplayName("traceId 形状不对：1001，且不去查库/不拼 URL")
    void rejectsMalformedTraceId() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsAdmin(auth);

            mockMvc.perform(get("/ai/admin/trace/{traceId}", "not a trace id"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1001));
        }

        verify(usageService, never()).traceCalls(anyString());
        verify(pythonAiClient, never()).traceReplay(anyString());
    }

    @Test
    @DisplayName("正常：Java 侧调用账 + Python 侧事件合到一处")
    void mergesBothHalves() throws Exception {
        when(usageService.traceCalls(TRACE)).thenReturn(List.of(call()));
        when(pythonAiClient.traceReplay(TRACE)).thenReturn(AiTraceDTO.builder()
                .traceId(TRACE)
                .found(true)
                .events(List.of(Map.of("kind", "retrieval", "posts", 3)))
                .build());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsAdmin(auth);

            mockMvc.perform(get("/ai/admin/trace/{traceId}", TRACE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.calls[0].scene").value("qa"))
                    .andExpect(jsonPath("$.data.calls[0].totalTokens").value(120))
                    .andExpect(jsonPath("$.data.events[0].kind").value("retrieval"))
                    .andExpect(jsonPath("$.data.pythonAvailable").value(true))
                    .andExpect(jsonPath("$.data.pythonFound").value(true));
        }
    }

    @Test
    @DisplayName("Python 没有这条链路：照回账，并说明「为什么没有」的两种可能")
    void pythonNotFoundStillReturnsTheLedger() throws Exception {
        when(usageService.traceCalls(TRACE)).thenReturn(List.of(call()));
        when(pythonAiClient.traceReplay(TRACE))
                .thenReturn(AiTraceDTO.builder().traceId(TRACE).found(false).events(List.of()).build());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsAdmin(auth);

            mockMvc.perform(get("/ai/admin/trace/{traceId}", TRACE))
                    .andExpect(jsonPath("$.data.calls[0].latencyMs").value(702))
                    .andExpect(jsonPath("$.data.pythonFound").value(false))
                    .andExpect(jsonPath("$.data.events").isEmpty())
                    .andExpect(jsonPath("$.data.notes[0]").value(
                            org.hamcrest.Matchers.containsString("缓冲是有界的")));
        }
    }

    @Test
    @DisplayName("Python 挂了：仍然回 Java 侧调用账（不能因为下游故障把已有的账也藏掉）")
    void pythonFailureDoesNotHideTheLedger() throws Exception {
        when(usageService.traceCalls(TRACE)).thenReturn(List.of(call()));
        when(pythonAiClient.traceReplay(TRACE))
                .thenThrow(new IllegalStateException("python down"));

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsAdmin(auth);

            mockMvc.perform(get("/ai/admin/trace/{traceId}", TRACE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.calls[0].scene").value("qa"))
                    .andExpect(jsonPath("$.data.pythonAvailable").value(false))
                    .andExpect(jsonPath("$.data.notes[0]").value(
                            org.hamcrest.Matchers.containsString("Python 侧回放不可用")));
        }
    }
}
