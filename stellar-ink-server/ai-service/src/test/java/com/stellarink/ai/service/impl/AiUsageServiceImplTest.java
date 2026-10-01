package com.stellarink.ai.service.impl;

import com.stellarink.aiclient.dto.UsageDTO;
import com.stellarink.ai.enums.AiCallScene;
import com.stellarink.ai.mapper.AiCallLogMapper;
import com.stellarink.ai.mapper.AiProviderConfigMapper;
import com.stellarink.ai.pojo.AiCallLog;
import com.stellarink.ai.pojo.AiProviderConfig;
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.vo.ai.AiUsageBreakdownVO;
import com.stellarink.sharedmodel.vo.ai.AiUsageSummaryVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调用账：记得到、算得对、缺口不藏。
 *
 * <p>跑完整上下文 + H2（与模型库测试同一套），因为要验的正是「真的落库了、单价真的被快照了」——
 * 用 mock 掉 Mapper 的单测证明不了列名与类型对得上。
 *
 * <p>三条断言是这个类存在的理由：
 * <ol>
 *   <li>成本 = tokens × 当时单价，且**单价在记账时快照**（事后改单价不改写历史账）；</li>
 *   <li>没配单价 / 上游没回报 token 的调用**分别计数**，绝不混进金额当 0；</li>
 *   <li>失败调用记 {@code success=0} 与异常类名，但**不进** token 与缺口统计。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("unittest")
class AiUsageServiceImplTest {

    /** 输入 1 元/百万、输出 2 元/百万：下面的 token 数刻意取整，成本一眼能验算 */
    private static final BigDecimal PRICE_IN = new BigDecimal("1.0000");

    private static final BigDecimal PRICE_OUT = new BigDecimal("2.0000");

    @Autowired
    private AiUsageService usageService;

    @Autowired
    private AiCallLogMapper callLogMapper;

    @Autowired
    private AiProviderConfigMapper providerConfigMapper;

    @AfterEach
    void cleanUp() {
        callLogMapper.delete(null);
        providerConfigMapper.delete(null);
    }

    /** 造一条 chat 角色配置（只填记账需要的列） */
    private void givenChatRoleWithPrice(BigDecimal priceIn, BigDecimal priceOut) {
        AiProviderConfig config = new AiProviderConfig();
        config.setRole("chat");
        config.setProvider("openai_compatible");
        config.setDisplayName("测试用 chat");
        config.setBaseUrl("https://example.invalid/v1");
        config.setModel("test-chat");
        config.setPriceInputPerMillion(priceIn);
        config.setPriceOutputPerMillion(priceOut);
        providerConfigMapper.insert(config);
    }

    private static UsageDTO usage(int prompt, int completion, int total, String model) {
        return UsageDTO.builder()
                .promptTokens(prompt)
                .completionTokens(completion)
                .totalTokens(total)
                .model(model)
                .build();
    }

    @Test
    @DisplayName("成本按 tokens × 记账时的单价算，并快照进这一行")
    void computesCostFromSnapshotPrice() {
        givenChatRoleWithPrice(PRICE_IN, PRICE_OUT);

        // 100 万输入 + 50 万输出 = 1.0000 + 1.0000 = 2.0000 元
        usageService.recordSuccess(
                AiCallScene.QA, usage(1_000_000, 500_000, 1_500_000, "test-chat"),
                System.currentTimeMillis());

        AiUsageSummaryVO summary = usageService.summary(1);

        assertEquals(1, summary.getCalls());
        assertEquals(1, summary.getSuccessCalls());
        assertEquals(0, summary.getFailedCalls());
        assertEquals(new BigDecimal("2.0000"), summary.getCost());
        assertEquals(0, summary.getUnpricedCalls());
        assertEquals(0, summary.getUntokenizedCalls());
        assertEquals(1_500_000L, summary.getTotalTokens());

        // 单价是**快照**：事后把角色单价改掉，历史账目的金额不该变
        AiProviderConfig config = providerConfigMapper.selectList(null).get(0);
        config.setPriceInputPerMillion(new BigDecimal("99.0000"));
        config.setPriceOutputPerMillion(new BigDecimal("99.0000"));
        providerConfigMapper.updateById(config);

        assertEquals(new BigDecimal("2.0000"), usageService.summary(1).getCost(),
                "成本必须用记账当时的单价，否则改一次价就把历史账目全改了");
    }

    @Test
    @DisplayName("没配单价：算不出成本，但调用数与被漏掉的条数都要如实说")
    void missingPriceIsCountedNotTreatedAsZero() {
        // 刻意不建角色配置：单价取不到 → 成本未知
        usageService.recordSuccess(
                AiCallScene.QA, usage(1000, 500, 1500, "test-chat"), System.currentTimeMillis());

        AiUsageSummaryVO summary = usageService.summary(1);

        assertEquals(1, summary.getCalls());
        assertEquals(1, summary.getUnpricedCalls(), "有 token 但没单价，必须被计成缺口");
        assertEquals(0, summary.getUntokenizedCalls());
        assertEquals(new BigDecimal("0.0000"), summary.getCost(),
                "没有一条可定价 → 金额是 0；**看板上必须同时看到 unpricedCalls=1**，否则这就是假数据");
    }

    @Test
    @DisplayName("上游没回报 token：计入未计量，且不算作「没配单价」")
    void untokenizedCallsAreCountedSeparately() {
        givenChatRoleWithPrice(PRICE_IN, PRICE_OUT);

        // 评测一轮：一次跑多个模型，token 目前不由 Python 回报
        usageService.recordSuccess(AiCallScene.EVAL, null, System.currentTimeMillis());

        AiUsageSummaryVO summary = usageService.summary(1);

        assertEquals(1, summary.getCalls());
        assertEquals(1, summary.getUntokenizedCalls());
        assertEquals(0, summary.getUnpricedCalls(), "没 token ≠ 没单价：两个缺口是两回事");
        assertEquals(0L, summary.getTotalTokens());
    }

    @Test
    @DisplayName("失败调用：记 success=0 与异常类名，但不进 token 与缺口统计")
    void failureIsRecordedButKeptOutOfUsageTotals() {
        givenChatRoleWithPrice(PRICE_IN, PRICE_OUT);

        usageService.recordFailure(AiCallScene.QA, new IllegalStateException("上游 502"),
                System.currentTimeMillis());

        AiUsageSummaryVO summary = usageService.summary(1);

        assertEquals(1, summary.getCalls());
        assertEquals(0, summary.getSuccessCalls());
        assertEquals(1, summary.getFailedCalls());
        assertEquals(0, summary.getUntokenizedCalls(),
                "失败调用没有用量是正常的，不能把它算成「未计量」");
        assertEquals(new BigDecimal("0.0000"), summary.getCost());
        assertEquals("IllegalStateException", callLogMapper.selectList(null).get(0).getErrorCode(),
                "失败分类只记异常类名，不记报文（报文可能含用户内容）");
    }

    @Test
    @DisplayName("around：异常原样抛回调用方，同时留下一条失败账")
    void aroundRethrowsAndStillRecords() {
        givenChatRoleWithPrice(PRICE_IN, PRICE_OUT);

        RuntimeException failure = assertThrows(IllegalStateException.class, () ->
                usageService.around(AiCallScene.QA, () -> {
                    throw new IllegalStateException("下游炸了");
                }, answer -> null));
        assertNotNull(failure);

        assertEquals(1, usageService.summary(1).getFailedCalls());
    }

    @Test
    @DisplayName("分组：按场景与按模型各一份，模型缺失归到「(未记录)」")
    void groupsBySceneAndModel() {
        givenChatRoleWithPrice(PRICE_IN, PRICE_OUT);

        usageService.recordSuccess(AiCallScene.QA, usage(10, 5, 15, "test-chat"),
                System.currentTimeMillis());
        usageService.recordSuccess(AiCallScene.QA, usage(10, 5, 15, "test-chat"),
                System.currentTimeMillis());
        usageService.recordSuccess(AiCallScene.EVAL, null, System.currentTimeMillis());

        AiUsageSummaryVO summary = usageService.summary(1);

        List<AiUsageBreakdownVO> byScene = summary.getByScene();
        assertEquals(2, byScene.size());
        assertEquals("qa", byScene.get(0).getKey(), "调用多的分组排前面");
        assertEquals(2, byScene.get(0).getCalls());
        assertEquals("eval", byScene.get(1).getKey());

        List<AiUsageBreakdownVO> byModel = summary.getByModel();
        assertEquals(2, byModel.size());
        assertEquals("test-chat", byModel.get(0).getKey());
        assertTrue(byModel.stream().anyMatch(item -> "(未记录)".equals(item.getKey())),
                "评测没有单一模型名，要归到一个看得懂的键上，而不是凭空消失");
    }

    @Test
    @DisplayName("窗口越界：报可读的参数错误，而不是查一个荒唐的范围")
    void rejectsUnreasonableWindows() {
        assertThrows(BusinessException.class, () -> usageService.summary(0));
        assertThrows(BusinessException.class, () -> usageService.summary(365));
    }

    @Test
    @DisplayName("记账号身份为空也要落库（无登录上下文时不能把调用变成 401）")
    void recordsEvenWithoutLoginContext() {
        givenChatRoleWithPrice(PRICE_IN, PRICE_OUT);

        // 测试里没有 Sa-Token 登录上下文：AuthHelper 会抛 401，记账路径必须自己吞掉
        usageService.recordSuccess(AiCallScene.QA, usage(10, 5, 15, "test-chat"),
                System.currentTimeMillis());

        List<AiCallLog> rows = callLogMapper.selectList(null);
        assertEquals(1, rows.size(), "拿不到身份也要记账，否则丢的是整条调用记录");
        assertNull(rows.get(0).getUserId());
    }
}
