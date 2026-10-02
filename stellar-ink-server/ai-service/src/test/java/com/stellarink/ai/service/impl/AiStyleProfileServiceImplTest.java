package com.stellarink.ai.service.impl;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.WritingStyleProfileDTO;
import com.stellarink.aiclient.dto.WritingStyleRequestDTO;
import com.stellarink.aiclient.dto.WritingStyleResultDTO;
import com.stellarink.ai.mapper.AiStyleProfileMapper;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.vo.ai.AiStyleProfileVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 派生风格画像的落库（M9-3b）。
 *
 * <p>它存在的理由是 M9 验收里那句「删除后同步清除**派生风格画像**」：
 * 清一个不存在的东西，看起来永远是对的 —— 所以必须先有东西可清、并有版本可说清清掉的是哪一版。
 *
 * <p>另一条同样重要：**样本不足时不落库**。存一版「样本不足」的画像比不存更糟 ——
 * 它看起来像一版正常画像，而下游会拿它当「这位作者的风格」用。
 */
@SpringBootTest
@ActiveProfiles("unittest")
class AiStyleProfileServiceImplTest {

    private static final long USER = 11L;

    @Autowired
    private AiStyleProfileServiceImpl service;

    @Autowired
    private AiStyleProfileMapper styleProfileMapper;

    @Autowired
    private AiMemoryServiceImpl memoryService;

    @Autowired
    private com.stellarink.ai.mapper.AiMemoryMapper memoryMapper;

    @Autowired
    private com.stellarink.ai.mapper.AiMemoryEvidenceMapper evidenceMapper;

    @MockBean
    private PythonAiClient pythonAiClient;

    @BeforeEach
    void clean() {
        evidenceMapper.delete(null);
        styleProfileMapper.delete(null);
        memoryMapper.delete(null);
    }

    private static WritingStyleResultDTO result(boolean sufficient) {
        return WritingStyleResultDTO.builder()
                .authorId(USER)
                .evidenceSufficient(sufficient)
                .profile(sufficient
                        ? WritingStyleProfileDTO.builder().sampleCount(12).build()
                        : null)
                .notes(sufficient ? "样本 12 篇" : "样本不足")
                .build();
    }

    @Test
    @DisplayName("刷新落库并递增版本：能说清「清掉的是哪一版」")
    void refreshStoresNewVersions() {
        when(pythonAiClient.writingStyle(any())).thenReturn(result(true));

        AiStyleProfileVO first = service.refresh(USER, USER);
        AiStyleProfileVO second = service.refresh(USER, USER);

        assertEquals(1, first.getVersion());
        assertEquals(2, second.getVersion());
        assertNotNull(second.getProfile());
        assertEquals(second.getVersion(), service.latest(USER).getVersion(), "取的是最新一版");
    }

    @Test
    @DisplayName("样本不足时不落库：存一版空画像看起来像正常画像")
    void insufficientEvidenceDoesNotStore() {
        when(pythonAiClient.writingStyle(any())).thenReturn(result(false));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.refresh(USER, USER));

        assertTrue(error.getMessage().contains("再多写几篇"), error.getMessage());
        assertEquals(0, styleProfileMapper.selectCount(null), "一行都不该落");
    }

    @Test
    @DisplayName("从没生成过时返回 null：「还没生成过」与「生成出来是空的」不是一回事")
    void latestIsNullBeforeFirstRefresh() {
        assertNull(service.latest(USER));
    }

    @Test
    @DisplayName("刷新时按登录者自己的文章统计（不会去统计别人的风格）")
    void refreshUsesTheGivenAuthor() {
        when(pythonAiClient.writingStyle(any())).thenReturn(result(true));

        service.refresh(USER, USER);

        org.mockito.ArgumentCaptor<WritingStyleRequestDTO> captor =
                org.mockito.ArgumentCaptor.forClass(WritingStyleRequestDTO.class);
        org.mockito.Mockito.verify(pythonAiClient).writingStyle(captor.capture());
        assertEquals(USER, captor.getValue().getAuthorId());
    }

    @Test
    @DisplayName("**删除联动**：先有画像，删掉记忆之后画像确实没了（清一个不存在的东西看起来永远是对的）")
    void deletingMemoryClearsTheProfile() {
        when(pythonAiClient.writingStyle(any())).thenReturn(result(true));
        service.refresh(USER, USER);
        assertEquals(1, styleProfileMapper.selectCount(null), "前提：画像已经落库");

        com.stellarink.ai.pojo.AiMemory memory = new com.stellarink.ai.pojo.AiMemory();
        memory.setUserId(USER);
        memory.setMemoryType("preference");
        memory.setContent("作者偏好短句");
        memory.setNormalized("作者偏好短句");
        memory.setConfidence(new java.math.BigDecimal("0.900"));
        memory.setSource("user_confirmed");
        memory.setStatus("active");
        memoryMapper.insert(memory);

        memoryService.delete(USER, memory.getId());

        assertEquals(0, styleProfileMapper.selectCount(null),
                "派生画像必须跟着删除一起清 —— 否则「删除」之后，画像里还留着从那些记忆推出来的特征");
    }

    @Test
    @DisplayName("全部清除也会清掉画像")
    void clearingAllMemoriesAlsoClearsTheProfile() {
        when(pythonAiClient.writingStyle(any())).thenReturn(result(true));
        service.refresh(USER, USER);

        memoryService.clearAll(USER);

        assertEquals(0, styleProfileMapper.selectCount(null));
    }
}
