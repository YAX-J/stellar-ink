package com.stellarink.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.ai.mapper.AiMemoryEvidenceMapper;
import com.stellarink.ai.mapper.AiMemoryMapper;
import com.stellarink.ai.mapper.AiStyleProfileMapper;
import com.stellarink.ai.pojo.AiMemory;
import com.stellarink.ai.pojo.AiMemoryEvidence;
import com.stellarink.ai.pojo.AiStyleProfile;
import com.stellarink.ai.service.AiMemoryService;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.vo.ai.AiMemoryEvidenceVO;
import com.stellarink.sharedmodel.vo.ai.AiMemoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 作者记忆的落库与状态流转实现（M9-2）。
 *
 * <p>四条口径：
 *
 * <ol>
 *   <li><b>每次取数都带 {@code user_id}</b>：不存在「按 id 取一条」的公开方法 ——
 *       那种方法早晚会被某个调用方忘了带用户条件，而它的表现是「别人的记忆出现在我的页面上」。</li>
 *   <li><b>删除是软删 + 清理</b>：状态置 {@code deleted}，同时清掉证据与派生画像。
 *       只改状态不清数据的话，「删除」之后画像里还留着从那些记忆推出来的特征 ——
 *       用户看到的界面干净了，数据却还在。</li>
 *   <li><b>用户主动禁用是禁用，不是删除</b>：{@code disabled} 保留数据与证据，
 *       因为它可以被恢复；把两者混成一件事，用户就无法「先关掉看看」。</li>
 *   <li><b>状态流转只允许 active ⇄ disabled</b>：删除走独立入口，避免
 *       「把 status 改成 deleted」绕过清理动作。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiMemoryServiceImpl implements AiMemoryService {

    /** 允许的状态白名单（与 `16_ai_memory.sql` 的注释一致）。 */
    private static final Set<String> STATUSES = Set.of("pending", "active", "disabled", "deleted");

    /** 用户可以在界面上切换的两档（删除与待确认都走各自的入口）。 */
    private static final Set<String> USER_TOGGLEABLE = Set.of("active", "disabled");

    private final AiMemoryMapper memoryMapper;
    private final AiMemoryEvidenceMapper evidenceMapper;
    private final AiStyleProfileMapper styleProfileMapper;

    @Override
    public List<AiMemoryVO> listMine(Long userId, String status, String type) {
        LambdaQueryWrapper<AiMemory> query = new LambdaQueryWrapper<AiMemory>()
                .eq(AiMemory::getUserId, userId);
        if (status != null && !status.isBlank()) {
            requireStatus(status);
            query.eq(AiMemory::getStatus, status);
        } else {
            // 默认不列已删除的：界面上「已删除」只该在明确的视图里出现
            query.ne(AiMemory::getStatus, "deleted");
        }
        if (type != null && !type.isBlank()) {
            query.eq(AiMemory::getMemoryType, type);
        }
        query.orderByDesc(AiMemory::getUpdatedAt).orderByDesc(AiMemory::getId);
        List<AiMemory> rows = memoryMapper.selectList(query);
        return withEvidence(rows);
    }

    @Override
    public AiMemoryVO setStatus(Long userId, Long memoryId, String status) {
        if (!USER_TOGGLEABLE.contains(status)) {
            // 明确拒绝而不是「顺手也支持 deleted」：删除有清理动作，走 setStatus 会绕过它
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR,
                    "只能启用或禁用记忆（删除请用删除接口）");
        }
        AiMemory memory = requireOwned(userId, memoryId);
        memory.setStatus(status);
        memoryMapper.updateById(memory);
        log.info("记忆状态变更：memoryId={} status={}", memoryId, status);
        return withEvidence(List.of(memory)).get(0);
    }

    @Override
    @Transactional
    public Long delete(Long userId, Long memoryId) {
        AiMemory memory = requireOwned(userId, memoryId);
        // ① 状态置 deleted（用户看得见的「已删除」）
        memory.setStatus("deleted");
        memoryMapper.updateById(memory);
        // ② 清证据：留着证据等于留着「这条记忆靠什么立起来」的全文
        evidenceMapper.delete(new LambdaQueryWrapper<AiMemoryEvidence>()
                .eq(AiMemoryEvidence::getMemoryId, memoryId));
        // ③ 清派生画像：它是从记忆与文章推出来的，删除之后不该再有
        styleProfileMapper.delete(new LambdaQueryWrapper<AiStyleProfile>()
                .eq(AiStyleProfile::getUserId, userId));
        log.info("记忆已删除并清理派生数据：memoryId={} userId={}", memoryId, userId);
        return memoryId;
    }

    @Override
    @Transactional
    public int clearAll(Long userId) {
        List<AiMemory> rows = memoryMapper.selectList(
                new LambdaQueryWrapper<AiMemory>().eq(AiMemory::getUserId, userId));
        if (rows.isEmpty()) {
            return 0;
        }
        List<Long> ids = rows.stream().map(AiMemory::getId).toList();
        // 全部清除是**硬清**：用户点了「全部清除」，留着行只会在下次构建时又被召回
        evidenceMapper.delete(new LambdaQueryWrapper<AiMemoryEvidence>()
                .in(AiMemoryEvidence::getMemoryId, ids));
        memoryMapper.delete(new LambdaQueryWrapper<AiMemory>().eq(AiMemory::getUserId, userId));
        styleProfileMapper.delete(new LambdaQueryWrapper<AiStyleProfile>()
                .eq(AiStyleProfile::getUserId, userId));
        log.info("记忆全部清除：userId={} 共 {} 条", userId, ids.size());
        return ids.size();
    }

    /**
     * 取一条**属于该用户**的记忆；不属于就当不存在。
     *
     * <p>刻意返回「不存在」而不是「无权限」：后者会告诉攻击者「这个 id 是有效的」。
     */
    private AiMemory requireOwned(Long userId, Long memoryId) {
        AiMemory memory = memoryMapper.selectOne(new LambdaQueryWrapper<AiMemory>()
                .eq(AiMemory::getId, memoryId)
                .eq(AiMemory::getUserId, userId));
        if (memory == null) {
            throw BusinessExceptionHelper.of(ErrorCode.NOT_FOUND, "记忆不存在");
        }
        return memory;
    }

    private void requireStatus(String status) {
        if (!STATUSES.contains(status)) {
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR, "未知的记忆状态：" + status);
        }
    }

    /** 一次性把证据查回来（避免 N+1：按 memoryId 批量取，再在内存里分组）。 */
    private List<AiMemoryVO> withEvidence(List<AiMemory> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(AiMemory::getId).toList();
        Map<Long, List<AiMemoryEvidence>> grouped = evidenceMapper
                .selectList(new LambdaQueryWrapper<AiMemoryEvidence>()
                        .in(AiMemoryEvidence::getMemoryId, ids))
                .stream()
                .collect(Collectors.groupingBy(AiMemoryEvidence::getMemoryId));

        List<AiMemoryVO> result = new ArrayList<>(rows.size());
        for (AiMemory memory : rows) {
            List<AiMemoryEvidenceVO> evidence = grouped
                    .getOrDefault(memory.getId(), List.of())
                    .stream()
                    .map(item -> AiMemoryEvidenceVO.builder()
                            .kind(item.getKind())
                            .ref(item.getRef())
                            .postId(item.getPostId())
                            .build())
                    .toList();
            result.add(AiMemoryVO.builder()
                    .id(memory.getId())
                    .memoryType(memory.getMemoryType())
                    .content(memory.getContent())
                    .confidence(memory.getConfidence() == null
                            ? null : memory.getConfidence().doubleValue())
                    .source(memory.getSource())
                    .status(memory.getStatus())
                    .confirmedAt(memory.getConfirmedAt())
                    .expiresAt(memory.getExpiresAt())
                    .createdAt(memory.getCreatedAt())
                    .evidence(evidence)
                    .build());
        }
        return result;
    }
}
