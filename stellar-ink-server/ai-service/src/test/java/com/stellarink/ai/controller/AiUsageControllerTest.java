package com.stellarink.ai.controller;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.ai.service.AiRetrievalAuditService;
import com.stellarink.ai.service.AiStyleProfileService;
import com.stellarink.ai.service.AiMemoryService;
import com.stellarink.ai.service.AiModelLibraryService;
import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.ai.service.AiWikiService;
import com.stellarink.common.advice.GlobalExceptionHandler;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.vo.ai.AiUsageBreakdownVO;
import com.stellarink.sharedmodel.vo.ai.AiUsageSummaryVO;
import com.stellarink.common.redis.RedisUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.stellarink.ai.corpus.service.CorpusSyncService;

/**
 * 成本看板出口：门槛、形状与「缺口不能被藏起来」。
 *
 * <p>这一层的价值全在**读法**上：金额与两个缺口计数必须一起出现。少一个，
 * 「这个月花了 ¥0.00」就会因为没配单价而看起来完全正常 —— 那是最难发现的一类错。
 */
@WebMvcTest(controllers = AiUsageController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("unittest")
class AiUsageControllerTest {

    /**
     * 语料投影同步：新增 @Service 后，本模块的 @WebMvcTest 切片必须把它 mock 掉 ——
     * 启动类显式声明了 @ComponentScan，切片会把它连同它的 Feign 客户端与 Mapper 一起装配，
     * 而 Web 切片里没有 FeignClientFactory、也没有 SqlSessionFactory（踩过一次：12 个切片全红）。
     */
    @MockBean
    private CorpusSyncService corpusSyncService;

    @Autowired
    private MockMvc mockMvc;

    /** 看板本身就是要测的对象，这里必须是真的 stub，不能用透传替身 */
    @MockBean
    private AiRetrievalAuditService auditService;

    @MockBean
    private AiStyleProfileService styleProfileService;

    @MockBean
    private AiMemoryService memoryService;

    @MockBean
    private AiUsageService usageService;

    /**
     * 配额用的 Redis 工具（E3-2）：切片里没有 spring-data-redis 的自动配置，
     * 而它是个独立装配的 @Component —— 不 mock 掉，整个切片上下文都起不来。
     * 这些用例不碰配额（AiUsageService 本身就是替身），所以它只是个占位。
     */
    @MockBean
    private RedisUtils redisUtils;

    /** 启动类显式声明了 @ComponentScan，切片会把别的控制器一起装配，它们的依赖要 mock 掉 */
    @MockBean
    private PythonAiClient pythonAiClient;

    @MockBean
    private AiProviderConfigService aiProviderConfigService;

    @MockBean
    private AiModelLibraryService modelLibraryService;

    /** Wiki 服务（E4-2）依赖 MyBatis Mapper：切片里不 mock 掉，整个上下文起不来。 */
    @MockBean
    private AiWikiService wikiService;

    private static AiUsageSummaryVO summary() {
        return AiUsageSummaryVO.builder()
                .days(7)
                .since(LocalDateTime.of(2026, 9, 25, 0, 0))
                .calls(12)
                .successCalls(11)
                .failedCalls(1)
                .promptTokens(9000L)
                .completionTokens(3000L)
                .totalTokens(12000L)
                .cost(new BigDecimal("0.4200"))
                .unpricedCalls(2)
                .untokenizedCalls(3)
                .byScene(List.of(AiUsageBreakdownVO.builder()
                        .key("qa").calls(9).totalTokens(11000L)
                        .cost(new BigDecimal("0.4000")).unpricedCalls(1).untokenizedCalls(0)
                        .build()))
                .byModel(List.of(AiUsageBreakdownVO.builder()
                        .key("deepseek-flash").calls(10).totalTokens(12000L)
                        .cost(new BigDecimal("0.4200")).unpricedCalls(2).untokenizedCalls(1)
                        .build()))
                .build();
    }

    @Test
    @DisplayName("未登录：401，且不查账（账里全是「谁用了多少」）")
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/ai/admin/usage/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        verify(usageService, never()).summary(anyInt());
    }

    @Test
    @DisplayName("读者：403（金额与用量是管理信息）")
    void requiresAdminRole() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::currentRole).thenReturn(Role.READER);
            auth.when(() -> AuthHelper.requireAtLeast(Role.ADMIN)).thenThrow(
                    new com.stellarink.sharedmodel.exception.BusinessException(
                            com.stellarink.sharedmodel.enums.ErrorCode.FORBIDDEN, "权限不足"));

            mockMvc.perform(get("/ai/admin/usage/summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        }

        verify(usageService, never()).summary(anyInt());
    }

    @Test
    @DisplayName("ADMIN：金额与两个缺口计数必须一起返回（否则 ¥0.00 会被当成「没花钱」）")
    void returnsCostTogetherWithGaps() throws Exception {
        when(usageService.summary(7)).thenReturn(summary());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::currentRole).thenReturn(Role.ADMIN);
            auth.when(() -> AuthHelper.requireAtLeast(Role.ADMIN)).thenAnswer(invocation -> null);

            mockMvc.perform(get("/ai/admin/usage/summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.calls").value(12))
                    .andExpect(jsonPath("$.data.failedCalls").value(1))
                    .andExpect(jsonPath("$.data.cost").value(0.4200))
                    .andExpect(jsonPath("$.data.unpricedCalls").value(2))
                    .andExpect(jsonPath("$.data.untokenizedCalls").value(3))
                    .andExpect(jsonPath("$.data.byScene[0].key").value("qa"))
                    .andExpect(jsonPath("$.data.byModel[0].key").value("deepseek-flash"));
        }
    }

    @Test
    @DisplayName("days 缺省为 7（面板不传参时也要有确定口径）")
    void defaultsToOneWeek() throws Exception {
        when(usageService.summary(7)).thenReturn(summary());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::currentRole).thenReturn(Role.ADMIN);
            auth.when(() -> AuthHelper.requireAtLeast(Role.ADMIN)).thenAnswer(invocation -> null);

            mockMvc.perform(get("/ai/admin/usage/summary"))
                    .andExpect(status().isOk());
        }

        verify(usageService).summary(7);
    }
}
