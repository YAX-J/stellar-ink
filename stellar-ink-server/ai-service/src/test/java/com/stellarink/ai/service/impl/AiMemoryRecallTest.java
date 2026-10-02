package com.stellarink.ai.service.impl;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.MemoryRecallRequestDTO;
import com.stellarink.aiclient.dto.MemoryRecallResultDTO;
import com.stellarink.ai.mapper.AiMemoryEvidenceMapper;
import com.stellarink.ai.mapper.AiMemoryMapper;
import com.stellarink.ai.mapper.AiStyleProfileMapper;
import com.stellarink.ai.pojo.AiMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆召回接线（M9-3）：问答/Agent 要把「这个用户当前可召回的记忆」交给 Python。
 *
 * <p>三条要守的：
 *
 * <ol>
 *   <li><b>用户隔离靠取数范围</b>：只查登录者自己的行，别人的记忆压根不进请求体；</li>
 *   <li><b>只送 active</b>：pending 是等人确认的、disabled 是用户主动关的、deleted 等清理 ——
 *       三类都不该进提示词；</li>
 *   <li><b>没有记忆就别打扰 Python</b>：这是最常见的情形，不该多一次内网往返。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("unittest")
class AiMemoryRecallTest {

    private static final long USER = 11L;
    private static final long OTHER = 22L;

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

    private AiMemory insert(long userId, String content, String status) {
        AiMemory memory = new AiMemory();
        memory.setUserId(userId);
        memory.setMemoryType("preference");
        memory.setContent(content);
        memory.setNormalized(content);
        memory.setConfidence(new BigDecimal("0.900"));
        memory.setSource("user_confirmed");
        memory.setStatus(status);
        memoryMapper.insert(memory);
        return memory;
    }

    @Test
    @DisplayName("只把自己 active 的记忆送进召回请求（用户隔离靠取数范围）")
    void onlyOwnActiveMemoriesAreSent() {
        AiMemory mine = insert(USER, "作者偏好短句", "active");
        insert(OTHER, "别人的偏好", "active");
        insert(USER, "待确认的", "pending");
        insert(USER, "被关掉的", "disabled");
        when(pythonAiClient.memoryRecall(any())).thenReturn(
                MemoryRecallResultDTO.builder().memoryIds(List.of(mine.getId())).notes(List.of()).build());

        List<String> recalled = service.listRecallable(USER, 5);

        ArgumentCaptor<MemoryRecallRequestDTO> captor =
                ArgumentCaptor.forClass(MemoryRecallRequestDTO.class);
        verify(pythonAiClient).memoryRecall(captor.capture());
        List<String> sent = captor.getValue().getMemories().stream()
                .map(item -> item.getContent())
                .toList();

        assertEquals(List.of("作者偏好短句"), sent,
                "别人的记忆、待确认的、被关掉的都不该进请求体");
        assertEquals(List.of("作者偏好短句"), recalled, "顺序按 Python 给的来（它负责排序）");
    }

    @Test
    @DisplayName("没有生效中的记忆时不调 Python：这是最常见的路径")
    void noActiveMemoriesSkipsPython() {
        insert(USER, "待确认的", "pending");

        assertTrue(service.listRecallable(USER, 5).isEmpty());
        verify(pythonAiClient, never()).memoryRecall(any());
    }

    @Test
    @DisplayName("Python 只放行一部分时，按它给的 id 取正文（过滤规则在那边）")
    void followsPythonOrderAndSelection() {
        AiMemory first = insert(USER, "记忆一", "active");
        AiMemory second = insert(USER, "记忆二", "active");
        when(pythonAiClient.memoryRecall(any())).thenReturn(MemoryRecallResultDTO.builder()
                // 故意反序、且只给一条：Java 不该自己重排或补全
                .memoryIds(List.of(second.getId(), first.getId()))
                .notes(List.of())
                .build());

        assertEquals(List.of("记忆二", "记忆一"), service.listRecallable(USER, 5));
    }

    @Test
    @DisplayName("过期时间只传有值的那些：区分「没设过期」与「已过期」")
    void expiresAtOnlyForRowsThatHaveIt() {
        AiMemory withExpiry = insert(USER, "会过期的", "active");
        withExpiry.setExpiresAt(LocalDateTime.of(2026, 12, 31, 0, 0));
        memoryMapper.updateById(withExpiry);
        AiMemory forever = insert(USER, "不过期的", "active");
        when(pythonAiClient.memoryRecall(any())).thenReturn(
                MemoryRecallResultDTO.builder().memoryIds(List.of(forever.getId())).notes(List.of()).build());

        service.listRecallable(USER, 5);

        ArgumentCaptor<MemoryRecallRequestDTO> captor =
                ArgumentCaptor.forClass(MemoryRecallRequestDTO.class);
        verify(pythonAiClient).memoryRecall(captor.capture());
        assertEquals(1, captor.getValue().getExpiresAtMs().size(),
                "只有设了过期时间的那条才出现在映射里");
        assertTrue(captor.getValue().getExpiresAtMs().containsKey(withExpiry.getId()));
    }
}
