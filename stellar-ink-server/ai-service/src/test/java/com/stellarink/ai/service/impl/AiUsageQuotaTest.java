package com.stellarink.ai.service.impl;

import com.stellarink.aiclient.dto.UsageDTO;
import com.stellarink.ai.config.AiQuotaProperties;
import com.stellarink.ai.enums.AiCallScene;
import com.stellarink.ai.mapper.AiCallLogMapper;
import com.stellarink.ai.mapper.AiProviderConfigMapper;
import com.stellarink.ai.pojo.AiCallLog;
import com.stellarink.ai.service.AiQuotaTicket;
import com.stellarink.ai.service.support.AiQuotaPolicy;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.redis.RedisUtils;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 配额与并发闸门（E3-2）。
 *
 * <p>这一层的价值全在**边界**上，所以每条断言都盯着一个具体的越界姿势：
 * 刚好等于上限算不算超、Redis 挂了会不会把 AI 全锁死、并发被拒之后闸门有没有还回去、
 * token 额度用完是拒「这一次」还是拒「下一次」。
 *
 * <p>用 mock 的 {@link RedisUtils} 而不是真的 Redis：配额的判定逻辑与 Redis 是否可用无关，
 * 真连一个 Redis 只会让「Redis 没装」变成测试失败的原因。
 */
class AiUsageQuotaTest {

    private static final long USER_ID = 7L;

    private AiCallLogMapper callLogMapper;

    private AiProviderConfigMapper providerConfigMapper;

    private RedisUtils redisUtils;

    private AiQuotaProperties properties;

    private AiUsageServiceImpl service;

    private String day;

    @BeforeEach
    void setUp() {
        callLogMapper = mock(AiCallLogMapper.class);
        providerConfigMapper = mock(AiProviderConfigMapper.class);
        redisUtils = mock(RedisUtils.class);
        properties = new AiQuotaProperties();
        properties.setEnabled(true);
        properties.setDailyCallsPerUser(3);
        properties.setDailyTokensPerUser(1000);
        properties.setDailyCallsPerRole(5);
        properties.setMaxConcurrentPerUser(2);
        service = new AiUsageServiceImpl(callLogMapper, providerConfigMapper, redisUtils, properties);
        day = AiQuotaPolicy.dayOf(LocalDate.now());
    }

    /** 把登录身份固定住：没有它，用户维度的配额根本没得测 */
    private static MockedStatic<AuthHelper> loggedIn() {
        MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class);
        auth.when(AuthHelper::loginId).thenReturn(USER_ID);
        auth.when(AuthHelper::currentRole).thenReturn(Role.READER);
        return auth;
    }

    private String inflightKey() {
        return AiQuotaPolicy.inflightKeyOfUser(USER_ID);
    }

    private static UsageDTO usage(int prompt, int completion, int total, String model) {
        return UsageDTO.builder()
                .promptTokens(prompt).completionTokens(completion).totalTokens(total).model(model)
                .build();
    }

    @Test
    @DisplayName("额度内：放行，并记下用户/角色两个计数与并发闸门")
    void withinLimitsReservesCallAndHoldsInflight() {
        when(redisUtils.increment(eq(inflightKey()), anyLong(), any(Duration.class))).thenReturn(1L);

        try (MockedStatic<AuthHelper> ignored = loggedIn()) {
            AiQuotaTicket ticket = service.acquireQuota(AiCallScene.QA);

            assertNotNull(ticket);
            assertEquals(USER_ID, ticket.userId());
            assertTrue(ticket.holdsInflight(), "并发闸门必须握在手里，否则 release 时会漏放");

            verify(redisUtils).increment(eq(AiQuotaPolicy.callsKeyOfUser(USER_ID, day)), eq(1L),
                    any(Duration.class));
            verify(redisUtils).increment(eq(AiQuotaPolicy.callsKeyOfRole("chat", day)), eq(1L),
                    any(Duration.class));
        }
    }

    @Test
    @DisplayName("调用数用满：429，且并发闸门必须还回去（否则这一天剩下的请求全被自己的并发上限挡住）")
    void dailyCallLimitIsEnforcedAndInflightIsReturned() {
        when(redisUtils.increment(eq(inflightKey()), anyLong(), any(Duration.class))).thenReturn(1L);
        when(redisUtils.get(AiQuotaPolicy.callsKeyOfUser(USER_ID, day), Long.class)).thenReturn(3L);

        try (MockedStatic<AuthHelper> ignored = loggedIn()) {
            BusinessException refused = assertThrows(BusinessException.class,
                    () -> service.acquireQuota(AiCallScene.QA));

            assertEquals(429, refused.getCode(), "配额触顶必须与前端 isRateLimited() 认的 429 对齐");
            assertTrue(refused.getMessage().contains("调用次数"));
        }

        verify(redisUtils).increment(inflightKey(), -1, Duration.ofSeconds(300));
        verify(redisUtils, never()).increment(eq(AiQuotaPolicy.callsKeyOfUser(USER_ID, day)), eq(1L),
                any(Duration.class));
    }

    @Test
    @DisplayName("token 额度用满：拒的是「本次」，并说清上限是多少")
    void tokenLimitIsEnforced() {
        when(redisUtils.increment(eq(inflightKey()), anyLong(), any(Duration.class))).thenReturn(1L);
        when(redisUtils.get(AiQuotaPolicy.callsKeyOfUser(USER_ID, day), Long.class)).thenReturn(0L);
        when(redisUtils.get(AiQuotaPolicy.tokensKeyOfUser(USER_ID, day), Long.class)).thenReturn(1000L);

        try (MockedStatic<AuthHelper> ignored = loggedIn()) {
            BusinessException refused = assertThrows(BusinessException.class,
                    () -> service.acquireQuota(AiCallScene.QA));

            assertEquals(429, refused.getCode());
            assertTrue(refused.getMessage().contains("token"));
        }
    }

    @Test
    @DisplayName("并发超限：429，并且不减闸门以外的计数")
    void concurrencyLimitIsEnforced() {
        when(redisUtils.increment(eq(inflightKey()), anyLong(), any(Duration.class))).thenReturn(3L);

        try (MockedStatic<AuthHelper> ignored = loggedIn()) {
            BusinessException refused = assertThrows(BusinessException.class,
                    () -> service.acquireQuota(AiCallScene.QA));

            assertEquals(429, refused.getCode());
            assertTrue(refused.getMessage().contains("同时进行"));
        }

        // 超限的那一次自己刚加的那 1 要减掉
        verify(redisUtils, times(2)).increment(eq(inflightKey()), anyLong(), any(Duration.class));
        verify(redisUtils).increment(inflightKey(), -1, Duration.ofSeconds(300));
    }

    @Test
    @DisplayName("Redis 不可用：放行（fail-open）而不是把整站 AI 锁死")
    void redisFailureFailsOpen() {
        when(redisUtils.increment(anyString(), anyLong(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));

        try (MockedStatic<AuthHelper> ignored = loggedIn()) {
            AiQuotaTicket ticket = service.acquireQuota(AiCallScene.QA);

            assertSame(AiQuotaTicket.NONE, ticket);
            assertFalse(ticket.holdsInflight());
        }
    }

    @Test
    @DisplayName("配额关闭：一次 Redis 都不碰")
    void disabledQuotaTouchesNothing() {
        properties.setEnabled(false);

        AiQuotaTicket ticket = service.acquireQuota(AiCallScene.QA);

        assertSame(AiQuotaTicket.NONE, ticket);
        verifyNoInteractions(redisUtils);
    }

    @Test
    @DisplayName("释放闸门：减到 0 及以下要把键删掉（TTL 过期后新建的 -1 会凭空多出一次并发额度）")
    void releaseDeletesTheKeyWhenItWouldGoNegative() {
        when(redisUtils.increment(eq("k"), anyLong(), any(Duration.class))).thenReturn(0L);

        service.releaseQuota(new AiQuotaTicket(USER_ID, "k"));

        verify(redisUtils).delete("k");
    }

    @Test
    @DisplayName("空票释放：什么都不做（配额关闭或 Redis 不可用时就是空票）")
    void releasingEmptyTicketIsNoop() {
        service.releaseQuota(AiQuotaTicket.NONE);
        service.releaseQuota(null);

        verifyNoInteractions(redisUtils);
    }

    @Test
    @DisplayName("调用之后才累加 token 与模型维度：模型名只有这时才知道")
    void countsTokensAndModelAfterTheCall() {
        when(redisUtils.increment(anyString(), anyLong(), any(Duration.class))).thenReturn(1L);
        when(callLogMapper.insert(any(AiCallLog.class))).thenReturn(1);

        try (MockedStatic<AuthHelper> ignored = loggedIn()) {
            service.recordSuccess(AiCallScene.QA, usage(100, 50, 150, "deepseek-flash"),
                    System.currentTimeMillis());
        }

        verify(redisUtils).increment(eq(AiQuotaPolicy.tokensKeyOfUser(USER_ID, day)), eq(150L),
                any(Duration.class));
        verify(redisUtils).increment(eq(AiQuotaPolicy.callsKeyOfModel("deepseek-flash", day)), eq(1L),
                any(Duration.class));
    }

    @Test
    @DisplayName("around：被配额拒绝时**不调用下游**，也不留下失败账")
    void quotaRefusalShortCircuitsTheCall() {
        when(redisUtils.increment(eq(inflightKey()), anyLong(), any(Duration.class))).thenReturn(1L);
        when(redisUtils.get(AiQuotaPolicy.callsKeyOfUser(USER_ID, day), Long.class)).thenReturn(3L);
        boolean[] called = {false};

        try (MockedStatic<AuthHelper> ignored = loggedIn()) {
            assertThrows(BusinessException.class, () -> service.around(
                    AiCallScene.QA,
                    () -> {
                        called[0] = true;
                        return "不该被调用";
                    },
                    answer -> null));
        }

        assertFalse(called[0], "配额拒绝必须是**调用前**的拦截，否则钱已经花了");
        verify(callLogMapper, never()).insert(any(AiCallLog.class));
    }
}
