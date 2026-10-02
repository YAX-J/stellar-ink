package com.stellarink.ai.service.impl;

import com.stellarink.aiclient.dto.CitationDTO;
import com.stellarink.ai.mapper.AiRetrievalAuditMapper;
import com.stellarink.ai.pojo.AiRetrievalAudit;
import com.stellarink.sharedmodel.vo.ai.AiRetrievalAuditSummaryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索审计（M8，H2 真落库）。
 *
 * <p>这张表补的是回放补不了的那一半：回放能看「这一次」的候选全文，
 * 而「最近七天哪类问题总被拒答」要看**趋势** —— 趋势才决定要不要补语料。
 *
 * <p>四条口径：
 *
 * <ol>
 *   <li><b>不存问题原文</b>：只存哈希与长度（问题里可能含个人信息），
 *       而要追原文就按 traceId 去回放看；</li>
 *   <li><b>拒答与失败分开</b>：拒答率高是「语料没覆盖」，失败率高是「链路坏了」，
 *       两者的处置完全不同；</li>
 *   <li><b>候选数与引用数分开</b>：差距大说明「召回了一堆但没一条够格进答案」；</li>
 *   <li><b>记录是 best-effort</b>：写不进去也不能让用户拿不到答案。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("unittest")
class AiRetrievalAuditServiceImplTest {

    private static final long USER = 11L;

    @Autowired
    private AiRetrievalAuditServiceImpl service;

    @Autowired
    private AiRetrievalAuditMapper auditMapper;

    @BeforeEach
    void clean() {
        auditMapper.delete(null);
    }

    private static CitationDTO citation(long postId, double score) {
        CitationDTO dto = new CitationDTO();
        dto.setPostId(postId);
        dto.setTitle("标题 " + postId);
        dto.setChunkIndex(0);
        dto.setScore(score);
        return dto;
    }

    @Test
    @DisplayName("记规模与结果，**不记问题原文**（只存哈希与长度）")
    void recordsShapeWithoutTheRawQuestion() {
        service.record(USER, "qa", "每天写五百字怎么坚持", List.of(citation(7, 0.82)), 12,
                false, 1500, "deepseek-chat");

        List<AiRetrievalAudit> rows = auditMapper.selectList(null);
        assertEquals(1, rows.size());
        AiRetrievalAudit row = rows.get(0);
        assertEquals(64, row.getQuestionHash().length(), "SHA-256");
        assertEquals(10, row.getQuestionChars());
        assertEquals(12, row.getCandidates(), "候选数是**召回**的规模");
        assertEquals(1, row.getCitations(), "引用数是**进答案**的条数，两者分开记");
        assertEquals("7", row.getPostIds());
        assertEquals(0, new BigDecimal("0.82").compareTo(row.getTopScore()));
        assertEquals("deepseek-chat", row.getModel());
        assertFalse(row.getRefused());
        assertFalse(row.getFailed());
    }

    @Test
    @DisplayName("拒答与失败分开记：拒答率高是语料没覆盖，失败率高是链路坏了")
    void refusalAndFailureAreDifferent() {
        // 有答案路径但一条引用都没有 → 拒答
        service.record(USER, "qa", "站里有讲量子计算的吗", List.of(), 3, false, 900, "m1");
        // 调用失败 → failed，不算拒答（拒答是「没有依据」，不是「坏了」）
        service.record(USER, "qa", "另一个问题", List.of(), 0, true, 200, "m2");

        List<AiRetrievalAudit> rows = auditMapper.selectList(null);
        assertTrue(rows.stream().anyMatch(row -> Boolean.TRUE.equals(row.getRefused())
                && Boolean.FALSE.equals(row.getFailed())));
        assertTrue(rows.stream().anyMatch(row -> Boolean.TRUE.equals(row.getFailed())
                && Boolean.FALSE.equals(row.getRefused())),
                "失败的那次不该同时被记成拒答");
    }

    @Test
    @DisplayName("同一篇文章被引用多次只算一次（审计看的是「哪几篇在撑场面」）")
    void postIdsAreDeduplicated() {
        service.record(USER, "qa", "问题", List.of(citation(7, 0.9), citation(7, 0.8)), 5,
                false, 100, "m");

        assertEquals("7", auditMapper.selectList(null).get(0).getPostIds());
    }

    @Test
    @DisplayName("没有引用时最高分为空（而不是 0：0 分与「没命中」是两件事）")
    void topScoreIsNullWithoutCitations() {
        service.record(USER, "qa", "问题", List.of(), 0, false, 100, "m");

        assertNull(auditMapper.selectList(null).get(0).getTopScore());
    }

    @Test
    @DisplayName("汇总：总数、拒答率、失败率、被引用最多的文章、反复被问的问题")
    void summarizeCountsTrends() {
        // 三次成功引用同一篇 + 一次拒答 + 一次失败
        service.record(USER, "qa", "问题甲", List.of(citation(7, 0.9)), 4, false, 100, "m");
        service.record(USER, "qa", "问题甲", List.of(citation(7, 0.85)), 4, false, 120, "m");
        service.record(USER, "qa", "问题乙", List.of(citation(9, 0.7)), 4, false, 130, "m");
        service.record(USER, "qa", "问题丙", List.of(), 2, false, 140, "m");
        service.record(USER, "qa", "问题丁", List.of(), 0, true, 150, "m");

        AiRetrievalAuditSummaryVO summary = service.summarize(7);

        assertEquals(5, summary.getTotal());
        assertEquals(1, summary.getRefused());
        assertEquals(1, summary.getFailed());
        assertEquals(0.2, summary.getRefusalRate(), 0.001);
        assertEquals(0.2, summary.getFailureRate(), 0.001);
        assertEquals(2, summary.getTopPosts().get(7L), "文章 7 被引用了两次");
        assertEquals(2, summary.getRepeatQuestions().values().stream().findFirst().orElse(0),
                "「问题甲」的哈希出现了两次 → 它是**复现性问题**（该补一篇的信号）");
        assertTrue(summary.getNotes().stream().anyMatch(note -> note.contains("拒答")),
                "汇总结论要写出来，而不是只给一堆数");
    }

    @Test
    @DisplayName("没有记录时如实说，而不是给一个「一切正常」的空汇总")
    void summarizeWithNoRows() {
        AiRetrievalAuditSummaryVO summary = service.summarize(7);

        assertEquals(0, summary.getTotal());
        assertEquals(0.0, summary.getRefusalRate(), 0.001);
        assertTrue(summary.getNotes().stream().anyMatch(note -> note.contains("没有检索记录")));
    }

    @Test
    @DisplayName("窗口之外的不计入（按天汇总要有边界）")
    void summarizeRespectsTheWindow() {
        AiRetrievalAudit old = new AiRetrievalAudit();
        old.setScene("qa");
        old.setTraceId("t-old");
        old.setQuestionHash("a".repeat(64));
        old.setQuestionChars(4);
        old.setCandidates(0);
        old.setCitations(0);
        old.setPostIds("");
        old.setRefused(false);
        old.setFailed(false);
        old.setLatencyMs(10);
        old.setModel("m");
        old.setCreatedAt(LocalDateTime.now().minusDays(30));
        auditMapper.insert(old);

        assertEquals(0, service.summarize(7).getTotal(), "30 天前的不该出现在「最近 7 天」里");
        assertEquals(1, service.summarize(90).getTotal());
    }

    @Test
    @DisplayName("记录是 best-effort：**mapper 抛错也不往外传**（不能让用户因为审计拿不到答案）")
    void recordNeverThrows() {
        // 直接注入一个必然抛错的 mapper：这才是这段代码真正要防的场景
        // （之前那条「传一堆 null 然后断言 service 不为空」的写法证明力为零，已替换）
        com.stellarink.ai.mapper.AiRetrievalAuditMapper failing =
                org.mockito.Mockito.mock(com.stellarink.ai.mapper.AiRetrievalAuditMapper.class);
        org.mockito.Mockito.when(failing.insert(org.mockito.ArgumentMatchers.any(AiRetrievalAudit.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("库挂了"));
        AiRetrievalAuditServiceImpl bestEffort = new AiRetrievalAuditServiceImpl(failing);

        // 不抛异常即通过
        bestEffort.record(USER, "qa", "问题", List.of(citation(7, 0.9)), 3, false, 100, "m");

        org.mockito.Mockito.verify(failing).insert(org.mockito.ArgumentMatchers.any(AiRetrievalAudit.class));
    }

    @Test
    @DisplayName("无论传什么（哪怕是 null）都不该抛：审计不能成为新的故障点")
    void recordToleratesNulls() {
        service.record(null, null, null, null, 0, false, null, null);

        List<AiRetrievalAudit> rows = auditMapper.selectList(null);
        assertEquals(1, rows.size(), "字段缺失时按默认值落库，而不是把异常丢给调用方");
        assertEquals(0, rows.get(0).getQuestionChars());
    }
}
