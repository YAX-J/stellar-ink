package com.stellarink.ai.service.impl;

import com.stellarink.ai.mapper.AiMemoryEvidenceMapper;
import com.stellarink.ai.mapper.AiMemoryMapper;
import com.stellarink.ai.mapper.AiStyleProfileMapper;
import com.stellarink.ai.pojo.AiMemory;
import com.stellarink.ai.pojo.AiMemoryEvidence;
import com.stellarink.ai.pojo.AiStyleProfile;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.vo.ai.AiMemoryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆的落库与状态流转（M9-2，H2 真落库）。
 *
 * <p>这一层守的是 M9 验收的第一、二条：
 *
 * <ul>
 *   <li><b>用户隔离</b>：每个方法都带 userId，别人的记忆既看不到也改不了；</li>
 *   <li><b>删除与禁用是真的</b>：删除要连证据与派生画像一起清（只改状态的话，
 *       界面上干净了、数据还在），禁用则要**保留**数据（它可以被恢复）。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("unittest")
class AiMemoryServiceImplTest {

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

    @BeforeEach
    void clean() {
        // H2 在同一个测试 JVM 里共享库：先清子表再清父表，避免残留影响断言
        evidenceMapper.delete(null);
        styleProfileMapper.delete(null);
        memoryMapper.delete(null);
    }

    private AiMemory insert(long userId, String type, String content, String status) {
        AiMemory memory = new AiMemory();
        memory.setUserId(userId);
        memory.setMemoryType(type);
        memory.setContent(content);
        memory.setNormalized(content.replace(" ", "").toLowerCase());
        memory.setConfidence(new BigDecimal("0.600"));
        memory.setSource("model_suggested");
        memory.setStatus(status);
        memoryMapper.insert(memory);
        return memory;
    }

    private void addEvidence(long memoryId, String ref) {
        AiMemoryEvidence evidence = new AiMemoryEvidence();
        evidence.setMemoryId(memoryId);
        evidence.setKind("quote");
        evidence.setRef(ref);
        evidenceMapper.insert(evidence);
    }

    private void addProfile(long userId) {
        AiStyleProfile profile = new AiStyleProfile();
        profile.setUserId(userId);
        profile.setVersion(1);
        profile.setPayload("{\"avgSentenceLength\":18}");
        styleProfileMapper.insert(profile);
    }

    @Test
    @DisplayName("列表只看得到自己的记忆，且默认不列已删除的")
    void listOnlyMine() {
        insert(USER, "preference", "偏好短句", "active");
        insert(USER, "fact", "写过 29 篇", "deleted");
        insert(OTHER, "preference", "别人的偏好", "active");

        List<AiMemoryVO> mine = service.listMine(USER, null, null);

        assertEquals(1, mine.size(), "既不能看到别人的，也不该列出已删除的");
        assertEquals("偏好短句", mine.get(0).getContent());
    }

    @Test
    @DisplayName("列表按状态与类型过滤；未知状态直接报参数错误")
    void listFilters() {
        insert(USER, "preference", "偏好短句", "active");
        insert(USER, "preference", "被关掉的偏好", "disabled");
        insert(USER, "fact", "事实", "active");

        assertEquals(1, service.listMine(USER, "disabled", null).size());
        assertEquals(2, service.listMine(USER, "active", null).size());
        assertEquals(1, service.listMine(USER, null, "fact").size());

        // 未知状态要报错而不是「当成不过滤」——后者会让人以为筛过了
        assertThrows(BusinessException.class, () -> service.listMine(USER, "unknown", null));
    }

    @Test
    @DisplayName("证据跟着列表一起回来：面板上「凭什么记住」和「记住什么」同等重要")
    void listCarriesEvidence() {
        AiMemory memory = insert(USER, "decision", "不再写第二季", "active");
        addEvidence(memory.getId(), "这个系列我决定不再写第二季了");

        AiMemoryVO vo = service.listMine(USER, null, null).get(0);

        assertEquals(1, vo.getEvidence().size());
        assertEquals("quote", vo.getEvidence().get(0).getKind());
        assertTrue(vo.getEvidence().get(0).getRef().contains("不再写第二季"));
    }

    @Test
    @DisplayName("禁用与启用是自己说了算；别人的记忆改不动")
    void toggleStatus() {
        AiMemory memory = insert(USER, "preference", "偏好短句", "active");

        assertEquals("disabled", service.setStatus(USER, memory.getId(), "disabled").getStatus());
        assertEquals("active", service.setStatus(USER, memory.getId(), "active").getStatus());

        assertThrows(BusinessException.class,
                () -> service.setStatus(OTHER, memory.getId(), "disabled"),
                "别人的记忆应当表现为「不存在」");
    }

    @Test
    @DisplayName("删除走独立入口：用 setStatus 传 deleted 会被拒（那样会绕过清理）")
    void deleteCannotBeReachedThroughSetStatus() {
        AiMemory memory = insert(USER, "preference", "偏好短句", "active");

        assertThrows(BusinessException.class,
                () -> service.setStatus(USER, memory.getId(), "deleted"));
    }

    @Test
    @DisplayName("删除会连证据与派生画像一起清掉")
    void deleteAlsoCleansDerivedData() {
        AiMemory memory = insert(USER, "preference", "偏好短句", "active");
        addEvidence(memory.getId(), "句子短一点读起来才顺");
        addProfile(USER);

        service.delete(USER, memory.getId());

        assertEquals("deleted", memoryMapper.selectById(memory.getId()).getStatus());
        assertTrue(evidenceMapper.selectList(null).isEmpty(), "证据也要清：它记着这句话的原文");
        assertTrue(styleProfileMapper.selectList(null).isEmpty(),
                "派生画像不清，「删除」之后画像里还留着从这些记忆推出来的特征");
    }

    @Test
    @DisplayName("删除别人的记忆：报「不存在」，别人的数据一点不动")
    void cannotDeleteOthers() {
        AiMemory memory = insert(USER, "preference", "偏好短句", "active");
        addEvidence(memory.getId(), "证据");

        assertThrows(BusinessException.class, () -> service.delete(OTHER, memory.getId()));

        assertEquals("active", memoryMapper.selectById(memory.getId()).getStatus());
        assertFalse(evidenceMapper.selectList(null).isEmpty());
    }

    @Test
    @DisplayName("全部清除：只清自己的，返回清掉的条数")
    void clearAllOnlyMine() {
        insert(USER, "preference", "偏好一", "active");
        insert(USER, "fact", "事实一", "disabled");
        AiMemory other = insert(OTHER, "preference", "别人的", "active");
        addEvidence(other.getId(), "别人的证据");
        addProfile(OTHER);

        int removed = service.clearAll(USER);

        assertEquals(2, removed);
        assertEquals(1, memoryMapper.selectList(null).size(), "别人的记忆必须留着");
        assertFalse(evidenceMapper.selectList(null).isEmpty(), "别人的证据也必须留着");
        assertFalse(styleProfileMapper.selectList(null).isEmpty(), "别人的画像也必须留着");
    }

    @Test
    @DisplayName("没有记忆时全部清除返回 0，而不是报错")
    void clearAllOnEmptyIsZero() {
        assertEquals(0, service.clearAll(USER));
    }
}
