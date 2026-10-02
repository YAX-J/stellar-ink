package com.stellarink.ai.service.impl;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.MemoryCandidateDTO;
import com.stellarink.aiclient.dto.MemoryConflictDTO;
import com.stellarink.aiclient.dto.MemoryDuplicateDTO;
import com.stellarink.aiclient.dto.MemoryEvidenceDTO;
import com.stellarink.aiclient.dto.MemoryExtractResultDTO;
import com.stellarink.aiclient.dto.MemoryExtractStatsDTO;
import com.stellarink.aiclient.dto.MemoryPlanResultDTO;
import com.stellarink.ai.mapper.AiMemoryEvidenceMapper;
import com.stellarink.ai.mapper.AiMemoryMapper;
import com.stellarink.ai.mapper.AiStyleProfileMapper;
import com.stellarink.ai.pojo.AiMemory;
import com.stellarink.ai.pojo.AiMemoryEvidence;
import com.stellarink.sharedmodel.vo.ai.AiMemoryConfirmVO;
import com.stellarink.sharedmodel.vo.ai.AiMemoryExtractVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 记忆的抽取与确认流程（M9-2c）。
 *
 * <p>这一层守的是 roadmap M9 第 2、3 条：
 *
 * <ul>
 *   <li><b>模型只生成候选</b>：抽出来落成 {@code pending}，**不参与召回** ——
 *       存下来不等于记住了；</li>
 *   <li><b>规则与用户确认决定是否持久化</b>：确认时新增转 active、重复合并证据、
 *       冲突**保持 pending 并报回**（不自动覆盖）。</li>
 * </ul>
 *
 * <p>判定由 Python 给出（这里 mock 掉），Java 只执行 —— 与「AI 能力在 Python、
 * 数据面在 Java」的边界一致。所以用例断言的是「Java 拿到三份清单后各自怎么处置」。
 */
@SpringBootTest
@ActiveProfiles("unittest")
class AiMemoryConfirmTest {

    private static final long USER = 11L;

    @Autowired
    private AiMemoryServiceImpl service;

    @Autowired
    private AiMemoryMapper memoryMapper;

    @Autowired
    private AiMemoryEvidenceMapper evidenceMapper;

    @Autowired
    private AiStyleProfileMapper styleProfileMapper;

    @MockBean
    private PythonAiClient pythonAiClient;

    @BeforeEach
    void clean() {
        evidenceMapper.delete(null);
        styleProfileMapper.delete(null);
        memoryMapper.delete(null);
    }

    private static MemoryCandidateDTO candidate(String type, String content, String normalized,
                                                String quote) {
        return MemoryCandidateDTO.builder()
                .type(type)
                .content(content)
                .normalized(normalized)
                .confidence(0.6)
                .source("model_suggested")
                .evidence(List.of(MemoryEvidenceDTO.builder().kind("quote").ref(quote).build()))
                .build();
    }

    private AiMemory insert(String type, String content, String normalized, String status) {
        AiMemory memory = new AiMemory();
        memory.setUserId(USER);
        memory.setMemoryType(type);
        memory.setContent(content);
        memory.setNormalized(normalized);
        memory.setConfidence(new BigDecimal("0.600"));
        memory.setSource("model_suggested");
        memory.setStatus(status);
        memoryMapper.insert(memory);
        return memory;
    }

    @Test
    @DisplayName("抽取：候选落成 pending，并带回丢弃计数与模型名")
    void extractStoresPendingCandidates() {
        when(pythonAiClient.memoryCandidates(any())).thenReturn(MemoryExtractResultDTO.builder()
                .candidates(List.of(candidate("preference", "作者偏好短句", "作者偏好短句", "句子短一点读起来才顺")))
                .stats(MemoryExtractStatsDTO.builder()
                        .proposed(3)
                        .kept(1)
                        .dropped(Map.of("noEvidence", 2))
                        .build())
                .notes(List.of("有 2 条出处对不上"))
                .usageModel("stub-model")
                .build());

        AiMemoryExtractVO result = service.extract(USER, "对话原文", 5);

        assertEquals(1, result.getKept());
        assertEquals(3, result.getProposed(), "提出数含被丢弃的，不能只报留下的");
        assertEquals(2, result.getDropped().get("noEvidence"));
        assertEquals("stub-model", result.getUsageModel());
        assertEquals("pending", result.getCandidates().get(0).getStatus(), "抽出来只是待确认");
        // 证据必须跟着落库：没有证据的记忆就是「模型印象」
        assertEquals(1, evidenceMapper.selectList(null).size());
        assertEquals(1, result.getCandidates().get(0).getEvidence().size());
    }

    @Test
    @DisplayName("确认：新增转 active（按用户确认档提高可信度）")
    void confirmActivatesNewMemories() {
        AiMemory pending = insert("preference", "作者偏好短句", "作者偏好短句", "pending");
        when(pythonAiClient.memoryPlan(any())).thenReturn(MemoryPlanResultDTO.builder()
                .toAdd(List.of(candidate("preference", "作者偏好短句", "作者偏好短句", "句子短一点")))
                .duplicates(List.of())
                .conflicts(List.of())
                .notes(List.of())
                .build());

        AiMemoryConfirmVO result = service.confirm(USER, List.of());

        assertEquals(1, result.getAdded());
        AiMemory stored = memoryMapper.selectById(pending.getId());
        assertEquals("active", stored.getStatus());
        assertEquals("user_confirmed", stored.getSource(), "来源要能说明「是用户点头的」");
        assertTrue(stored.getConfidence().doubleValue() > 0.7,
                "用户确认过的可信度要高于模型推测的封顶");
        assertNotNull(stored.getConfirmedAt());
    }

    @Test
    @DisplayName("确认：重复合并证据、删掉待确认那条，且**不动已有正文**")
    void confirmMergesDuplicatesWithoutRewriting() {
        AiMemory active = insert("preference", "作者偏好短句", "作者偏好短句", "active");
        AiMemory pending = insert("preference", "作者偏好短句", "作者偏好短句", "pending");
        evidenceMapper.insert(evidence(pending.getId(), "句子短一点读起来才顺"));
        when(pythonAiClient.memoryPlan(any())).thenReturn(MemoryPlanResultDTO.builder()
                .toAdd(List.of())
                .duplicates(List.of(MemoryDuplicateDTO.builder()
                        .memoryId(active.getId())
                        .content("作者偏好短句")
                        .enriched(true)
                        .build()))
                .conflicts(List.of())
                .notes(List.of())
                .build());

        AiMemoryConfirmVO result = service.confirm(USER, List.of());

        assertEquals(1, result.getMerged());
        assertEquals("作者偏好短句", memoryMapper.selectById(active.getId()).getContent(),
                "合并只补证据，正文一个字都不改 —— 改正文等于替用户改记忆");
        // 证据已经挂到已有那条上，待确认那条连证据一起删掉
        List<AiMemoryEvidence> evidence = evidenceMapper.selectList(null);
        assertEquals(1, evidence.size());
        assertEquals(active.getId(), evidence.get(0).getMemoryId());
        assertEquals(1, memoryMapper.selectCount(null), "待确认那条应当被合并掉了");
    }

    @Test
    @DisplayName("确认：冲突**保持待确认**并原样报回（不自动覆盖）")
    void confirmKeepsConflictsPending() {
        AiMemory active = insert("preference", "作者偏好把文章写长，一次讲透", "作者偏好把文章写长，一次讲透", "active");
        AiMemory pending = insert("preference", "作者偏好把文章写短，一次只讲一件事",
                "作者偏好把文章写短，一次只讲一件事", "pending");
        when(pythonAiClient.memoryPlan(any())).thenReturn(MemoryPlanResultDTO.builder()
                .toAdd(List.of())
                .duplicates(List.of())
                .conflicts(List.of(MemoryConflictDTO.builder()
                        .memoryId(active.getId())
                        .existingContent("作者偏好把文章写长，一次讲透")
                        .candidateContent("作者偏好把文章写短，一次只讲一件事")
                        .build()))
                .notes(List.of("1 条候选与已有记忆措辞相近但结论不同 —— 不自动覆盖"))
                .build());

        AiMemoryConfirmVO result = service.confirm(USER, List.of());

        assertEquals(0, result.getAdded());
        assertEquals(1, result.getConflicts().size());
        assertEquals(active.getId(), result.getConflicts().get(0).getMemoryId());
        assertEquals(pending.getId(), result.getConflicts().get(0).getCandidateId(),
                "要带上待确认那条的 id：用户选「用新的替换」时需要它");
        assertEquals("pending", memoryMapper.selectById(pending.getId()).getStatus(),
                "冲突**不能**被确认成 active");
        assertEquals("作者偏好把文章写长，一次讲透",
                memoryMapper.selectById(active.getId()).getContent(), "已有那条也不能被改写");
        assertTrue(result.getNotes().stream().anyMatch(note -> note.contains("冲突")));
    }

    @Test
    @DisplayName("抽取：同一段对话抽两次不会重复落库（也绝不能抛 DuplicateKey）")
    void extractIsIdempotentForTheSameConversation() {
        when(pythonAiClient.memoryCandidates(any())).thenReturn(MemoryExtractResultDTO.builder()
                .candidates(List.of(candidate("preference", "作者偏好短句", "作者偏好短句", "句子短一点")))
                .stats(MemoryExtractStatsDTO.builder().proposed(1).kept(1).dropped(Map.of()).build())
                .notes(List.of())
                .build());

        service.extract(USER, "对话原文", 5);
        AiMemoryExtractVO second = service.extract(USER, "对话原文", 5);

        assertEquals(1, memoryMapper.selectCount(null), "第二次抽取不该多出一行");
        assertTrue(second.getNotes().stream().anyMatch(note -> note.contains("未重复落库")));
    }

    @Test
    @DisplayName("确认：没有待确认的记忆时如实说，不报错")
    void confirmWithNothingPending() {
        AiMemoryConfirmVO result = service.confirm(USER, List.of());

        assertEquals(0, result.getAdded());
        assertTrue(result.getNotes().stream().anyMatch(note -> note.contains("没有待确认")));
    }

    @Test
    @DisplayName("确认只动自己点名的那些：别人的待确认不受影响")
    void confirmOnlyTouchesNamedOnes() {
        AiMemory first = insert("preference", "偏好一", "偏好一", "pending");
        AiMemory second = insert("preference", "偏好二", "偏好二", "pending");
        when(pythonAiClient.memoryPlan(any())).thenReturn(MemoryPlanResultDTO.builder()
                .toAdd(List.of(candidate("preference", "偏好一", "偏好一", "原文一")))
                .duplicates(List.of())
                .conflicts(List.of())
                .notes(List.of())
                .build());

        service.confirm(USER, List.of(first.getId()));

        assertEquals("active", memoryMapper.selectById(first.getId()).getStatus());
        assertEquals("pending", memoryMapper.selectById(second.getId()).getStatus(),
                "只确认点名的那条，其余留在待确认");
    }

    private static AiMemoryEvidence evidence(Long memoryId, String ref) {
        AiMemoryEvidence row = new AiMemoryEvidence();
        row.setMemoryId(memoryId);
        row.setKind("quote");
        row.setRef(ref);
        return row;
    }
}
