package com.stellarink.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.MemoryCandidateDTO;
import com.stellarink.aiclient.dto.MemoryConflictDTO;
import com.stellarink.aiclient.dto.MemoryDuplicateDTO;
import com.stellarink.aiclient.dto.MemoryEvidenceDTO;
import com.stellarink.aiclient.dto.MemoryExtractRequestDTO;
import com.stellarink.aiclient.dto.MemoryExtractResultDTO;
import com.stellarink.aiclient.dto.MemoryPlanRequestDTO;
import com.stellarink.aiclient.dto.MemoryPlanResultDTO;
import com.stellarink.aiclient.dto.MemoryRecallRequestDTO;
import com.stellarink.aiclient.dto.MemoryRecallResultDTO;
import com.stellarink.aiclient.dto.MemoryRecordDTO;
import com.stellarink.ai.mapper.AiMemoryEvidenceMapper;
import com.stellarink.ai.mapper.AiMemoryMapper;
import com.stellarink.ai.mapper.AiStyleProfileMapper;
import com.stellarink.ai.pojo.AiMemory;
import com.stellarink.ai.pojo.AiMemoryEvidence;
import com.stellarink.ai.pojo.AiStyleProfile;
import com.stellarink.ai.service.AiMemoryService;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.vo.ai.AiMemoryConflictVO;
import com.stellarink.sharedmodel.vo.ai.AiMemoryConfirmVO;
import com.stellarink.sharedmodel.vo.ai.AiMemoryEvidenceVO;
import com.stellarink.sharedmodel.vo.ai.AiMemoryExtractVO;
import com.stellarink.sharedmodel.vo.ai.AiMemoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 作者记忆的落库、状态流转与确认流程（M9）。
 *
 * <p>五条口径：
 *
 * <ol>
 *   <li><b>每次取数都带 {@code user_id}</b>：不存在「按 id 取一条」的公开方法 ——
 *       那种方法早晚会被某个调用方忘了带用户条件，而它的表现是「别人的记忆出现在我的页面上」。</li>
 *   <li><b>抽出来只落 pending</b>：候选要能被「稍后再看」，而 pending **不参与召回** ——
 *       存下来不等于记住了（roadmap M9：模型只生成候选，规则与用户确认决定是否持久化）。</li>
 *   <li><b>确认时冲突不自动覆盖</b>：判定由 Python 的规则给出，Java 只执行；
 *       冲突保持 pending 并报给用户 —— 可能是同义改写，也可能是作者改了主意。</li>
 *   <li><b>合并重复时不动已有正文</b>：只把新证据补上去。改正文等于替用户改记忆。</li>
 *   <li><b>删除是软删 + 清理</b>：状态置 {@code deleted}，同时清证据与派生画像。
 *       只改状态不清数据的话，「删除」之后画像里还留着从那些记忆推出来的特征。</li>
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

    /** 用户确认过的记忆按这个可信度记：它比模型推测（封顶 0.7）更可信，这个差值是召回排序的依据。 */
    private static final double USER_CONFIRMED_CONFIDENCE = 0.9;

    private final AiMemoryMapper memoryMapper;
    private final AiMemoryEvidenceMapper evidenceMapper;
    private final AiStyleProfileMapper styleProfileMapper;
    private final PythonAiClient pythonAiClient;

    // ------------------------------------------------------------------ 召回

    @Override
    public List<String> listRecallable(Long userId, int limit) {
        List<AiMemory> active = memoryMapper.selectList(new LambdaQueryWrapper<AiMemory>()
                .eq(AiMemory::getUserId, userId)
                .eq(AiMemory::getStatus, "active"));
        if (active.isEmpty()) {
            // 没有记忆就别去调 Python：这是最常见的路径，不该多一次内网往返
            return List.of();
        }
        Map<Long, AiMemory> byId = active.stream()
                .collect(Collectors.toMap(AiMemory::getId, item -> item));
        MemoryRecallResultDTO recalled = pythonAiClient.memoryRecall(
                MemoryRecallRequestDTO.builder()
                        .memories(active.stream().map(this::toRecordDTO).toList())
                        .minConfidence(0.0)
                        .limit(limit)
                        .expiresAtMs(expiresAtOf(active))
                        .build());
        List<String> contents = new ArrayList<>();
        for (Long memoryId : orEmpty(recalled.getMemoryIds())) {
            AiMemory memory = byId.get(memoryId);
            if (memory != null) {
                contents.add(memory.getContent());
            }
        }
        return contents;
    }

    /** 过期时间只在有值时传：区分「没设过期」与「已过期」是 Python 侧过滤的判据。 */
    private Map<Long, Long> expiresAtOf(List<AiMemory> rows) {
        Map<Long, Long> result = new java.util.HashMap<>();
        for (AiMemory memory : rows) {
            if (memory.getExpiresAt() != null) {
                result.put(memory.getId(), memory.getExpiresAt()
                        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli());
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 抽取

    @Override
    @Transactional
    public AiMemoryExtractVO extract(Long userId, String conversation, Integer maxCandidates) {
        MemoryExtractResultDTO result = pythonAiClient.memoryCandidates(
                MemoryExtractRequestDTO.builder()
                        .conversation(conversation)
                        .maxCandidates(maxCandidates == null ? 5 : maxCandidates)
                        .source("model_suggested")
                        .build());

        List<AiMemoryVO> stored = new ArrayList<>();
        int reused = 0;
        for (MemoryCandidateDTO candidate : orEmpty(result.getCandidates())) {
            String normalized = orNormalized(candidate);
            // 同一段对话抽两次是常态：已存在的 pending **不重复落库**，
            // 也不能抛 DuplicateKey（那会让「点了两次抽取」表现成 500）
            AiMemory memory = findPendingByAnchor(userId, candidate.getType(), normalized);
            if (memory != null) {
                reused++;
                saveEvidence(memory.getId(), candidate.getEvidence());
                stored.add(toView(memory, candidate.getEvidence()));
                continue;
            }
            memory = new AiMemory();
            memory.setUserId(userId);
            memory.setMemoryType(candidate.getType());
            memory.setContent(candidate.getContent());
            memory.setNormalized(normalized);
            memory.setConfidence(toDecimal(candidate.getConfidence()));
            memory.setSource(candidate.getSource() == null
                    ? "model_suggested" : candidate.getSource());
            // 落 pending：**存下来 ≠ 记住了**（pending 不参与召回）
            memory.setStatus("pending");
            memoryMapper.insert(memory);
            saveEvidence(memory.getId(), candidate.getEvidence());
            stored.add(toView(memory, candidate.getEvidence()));
        }

        Map<String, Integer> dropped = result.getStats() == null
                || result.getStats().getDropped() == null
                ? Map.of() : result.getStats().getDropped();
        int proposed = result.getStats() == null || result.getStats().getProposed() == null
                ? stored.size() : result.getStats().getProposed();
        List<String> notes = new ArrayList<>(orEmpty(result.getNotes()));
        if (reused > 0) {
            notes.add(String.format(
                    "%d 条候选已经在这批待确认里了，未重复落库（证据已补上）。", reused));
        }
        log.info("记忆候选抽取：用户 {} 提出 {} 条，落 pending {} 条（其中复用 {}），丢弃 {}",
                userId, proposed, stored.size(), reused, dropped);
        return AiMemoryExtractVO.builder()
                .candidates(stored)
                .proposed(proposed)
                .kept(stored.size())
                .dropped(dropped)
                .notes(notes)
                .usageModel(result.getUsageModel())
                .build();
    }

    // ------------------------------------------------------------------ 确认

    @Override
    @Transactional
    public AiMemoryConfirmVO confirm(Long userId, List<Long> memoryIds) {
        LambdaQueryWrapper<AiMemory> pendingQuery = new LambdaQueryWrapper<AiMemory>()
                .eq(AiMemory::getUserId, userId)
                .eq(AiMemory::getStatus, "pending");
        if (memoryIds != null && !memoryIds.isEmpty()) {
            pendingQuery.in(AiMemory::getId, memoryIds);
        }
        List<AiMemory> pending = memoryMapper.selectList(pendingQuery);
        if (pending.isEmpty()) {
            return AiMemoryConfirmVO.builder()
                    .added(0)
                    .merged(0)
                    .conflicts(List.of())
                    .notes(List.of("没有待确认的记忆。"))
                    .build();
        }

        // 判定的输入 = 已生效的记忆 + 待确认的候选（规则只在 Python 那边有一份）
        List<AiMemory> active = memoryMapper.selectList(new LambdaQueryWrapper<AiMemory>()
                .eq(AiMemory::getUserId, userId)
                .eq(AiMemory::getStatus, "active"));
        Map<Long, List<MemoryEvidenceDTO>> evidence = evidenceOf(pending);
        MemoryPlanResultDTO plan = pythonAiClient.memoryPlan(MemoryPlanRequestDTO.builder()
                .existing(active.stream().map(this::toRecordDTO).toList())
                .candidates(pending.stream()
                        .map(item -> toCandidateDTO(item, evidence))
                        .toList())
                .build());

        int added = 0;
        int merged = 0;
        for (MemoryCandidateDTO candidate : orEmpty(plan.getToAdd())) {
            AiMemory memory = findPending(pending, candidate);
            if (memory == null) {
                continue;
            }
            memory.setStatus("active");
            // 用户确认过的按 `user_confirmed` 记：这是它比模型推测更可信的依据
            memory.setSource("user_confirmed");
            memory.setConfidence(BigDecimal.valueOf(USER_CONFIRMED_CONFIDENCE));
            memory.setConfirmedAt(LocalDateTime.now());
            memoryMapper.updateById(memory);
            added++;
        }
        for (MemoryDuplicateDTO duplicate : orEmpty(plan.getDuplicates())) {
            AiMemory memory = findPendingByContent(pending, duplicate.getContent());
            if (memory == null) {
                continue;
            }
            // 合并：证据补给已有那条，**已有正文不动**
            saveEvidence(duplicate.getMemoryId(), evidence.get(memory.getId()));
            deleteRow(memory.getId());
            merged++;
        }

        List<AiMemoryConflictVO> conflicts = new ArrayList<>();
        for (MemoryConflictDTO conflict : orEmpty(plan.getConflicts())) {
            AiMemory candidate = findPendingByContent(pending, conflict.getCandidateContent());
            conflicts.add(AiMemoryConflictVO.builder()
                    .memoryId(conflict.getMemoryId())
                    .existingContent(conflict.getExistingContent())
                    .candidateContent(conflict.getCandidateContent())
                    .candidateId(candidate == null ? null : candidate.getId())
                    .build());
        }
        List<String> notes = new ArrayList<>(orEmpty(plan.getNotes()));
        if (!conflicts.isEmpty()) {
            notes.add(String.format(
                    "%d 条候选与已有记忆冲突，仍留在待确认里 —— 请选择保留哪一条。", conflicts.size()));
        }
        log.info("记忆确认：用户 {} 新增 {} 条、合并 {} 条、冲突 {} 条",
                userId, added, merged, conflicts.size());
        return AiMemoryConfirmVO.builder()
                .added(added)
                .merged(merged)
                .conflicts(conflicts)
                .notes(notes)
                .build();
    }

    // ------------------------------------------------------------------ 列表与状态

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
        return withEvidence(memoryMapper.selectList(query));
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
        // ⚠️ 派生画像**先清、且不受「有没有记忆」影响**：
        // 曾经把这一步放在「没有记忆就 return 0」之后，于是「一条记忆都没有、但有画像」的用户
        // 点「全部清除」会一无所获 —— 用户以为清干净了，画像还在（测试抓出来的）
        styleProfileMapper.delete(new LambdaQueryWrapper<AiStyleProfile>()
                .eq(AiStyleProfile::getUserId, userId));
        if (rows.isEmpty()) {
            return 0;
        }
        List<Long> ids = rows.stream().map(AiMemory::getId).toList();
        // 全部清除是**硬清**：用户点了「全部清除」，留着行只会在下次构建时又被召回
        evidenceMapper.delete(new LambdaQueryWrapper<AiMemoryEvidence>()
                .in(AiMemoryEvidence::getMemoryId, ids));
        memoryMapper.delete(new LambdaQueryWrapper<AiMemory>().eq(AiMemory::getUserId, userId));
        log.info("记忆全部清除：userId={} 共 {} 条", userId, ids.size());
        return ids.size();
    }

    // ------------------------------------------------------------------ 内部

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

    /** 待确认的行按「内容 + 类型」找回原行（判定来自 Python，回来的是候选正文）。 */
    private AiMemory findPending(List<AiMemory> pending, MemoryCandidateDTO candidate) {
        return pending.stream()
                .filter(item -> item.getMemoryType().equals(candidate.getType())
                        && item.getContent().equals(candidate.getContent()))
                .findFirst()
                .orElse(null);
    }

    private AiMemory findPendingByContent(List<AiMemory> pending, String content) {
        return pending.stream()
                .filter(item -> item.getContent().equals(content))
                .findFirst()
                .orElse(null);
    }

    /** 按锚点找一条**待确认**的记忆（同一段对话抽两次时用它去重）。 */
    private AiMemory findPendingByAnchor(Long userId, String type, String normalized) {
        return memoryMapper.selectOne(new LambdaQueryWrapper<AiMemory>()
                .eq(AiMemory::getUserId, userId)
                .eq(AiMemory::getMemoryType, type)
                .eq(AiMemory::getNormalized, normalized)
                .eq(AiMemory::getStatus, "pending"));
    }

    /** 归一化正文缺失时**不猜**：用正文兜底并告警（幂等判定会因此不精确）。 */
    private String orNormalized(MemoryCandidateDTO candidate) {
        String normalized = candidate.getNormalized();
        if (normalized == null || normalized.isBlank()) {
            log.warn("候选缺少归一化正文，退回正文本身（幂等判定会因此不精确）：{}",
                    candidate.getContent());
            return candidate.getContent();
        }
        return normalized;
    }

    private String normalizedOf(AiMemory memory) {
        return memory.getNormalized() == null ? "" : memory.getNormalized();
    }

    private MemoryRecordDTO toRecordDTO(AiMemory memory) {
        return MemoryRecordDTO.builder()
                .memoryId(memory.getId())
                .type(memory.getMemoryType())
                .content(memory.getContent())
                .confidence(memory.getConfidence() == null
                        ? null : memory.getConfidence().doubleValue())
                .status(memory.getStatus())
                .evidence(evidenceDTOs(memory.getId()))
                .build();
    }

    private MemoryCandidateDTO toCandidateDTO(AiMemory memory, Map<Long, List<MemoryEvidenceDTO>> evidence) {
        return MemoryCandidateDTO.builder()
                .type(memory.getMemoryType())
                .content(memory.getContent())
                .normalized(normalizedOf(memory))
                .confidence(memory.getConfidence() == null
                        ? null : memory.getConfidence().doubleValue())
                .source(memory.getSource())
                .evidence(orEmpty(evidence.get(memory.getId())))
                .build();
    }

    private List<MemoryEvidenceDTO> evidenceDTOs(Long memoryId) {
        return evidenceMapper.selectList(new LambdaQueryWrapper<AiMemoryEvidence>()
                        .eq(AiMemoryEvidence::getMemoryId, memoryId))
                .stream()
                .map(AiMemoryServiceImpl::toEvidenceDTO)
                .toList();
    }

    private static MemoryEvidenceDTO toEvidenceDTO(AiMemoryEvidence item) {
        return MemoryEvidenceDTO.builder()
                .kind(item.getKind())
                .ref(item.getRef())
                .postId(item.getPostId())
                .build();
    }

    private Map<Long, List<MemoryEvidenceDTO>> evidenceOf(List<AiMemory> rows) {
        List<Long> ids = rows.stream().map(AiMemory::getId).toList();
        return evidenceMapper.selectList(new LambdaQueryWrapper<AiMemoryEvidence>()
                        .in(AiMemoryEvidence::getMemoryId, ids))
                .stream()
                .collect(Collectors.groupingBy(
                        AiMemoryEvidence::getMemoryId,
                        Collectors.mapping(AiMemoryServiceImpl::toEvidenceDTO, Collectors.toList())));
    }

    private void saveEvidence(Long memoryId, List<MemoryEvidenceDTO> evidence) {
        for (MemoryEvidenceDTO item : orEmpty(evidence)) {
            AiMemoryEvidence row = new AiMemoryEvidence();
            row.setMemoryId(memoryId);
            row.setKind(item.getKind());
            row.setRef(item.getRef());
            row.setPostId(item.getPostId());
            evidenceMapper.insert(row);
        }
    }

    private void deleteRow(Long memoryId) {
        evidenceMapper.delete(new LambdaQueryWrapper<AiMemoryEvidence>()
                .eq(AiMemoryEvidence::getMemoryId, memoryId));
        memoryMapper.deleteById(memoryId);
    }

    private static BigDecimal toDecimal(Double value) {
        return BigDecimal.valueOf(value == null ? 0.5 : value);
    }

    /** 一次性把证据查回来（避免 N+1：按 memoryId 批量取，再在内存里分组）。 */
    private List<AiMemoryVO> withEvidence(List<AiMemory> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<MemoryEvidenceDTO>> grouped = evidenceOf(rows);
        return rows.stream()
                .map(memory -> toView(memory, grouped.getOrDefault(memory.getId(), List.of())))
                .toList();
    }

    /** 把一条行 + 它的证据装成对外视图（列表与抽取共用）。 */
    private AiMemoryVO toView(AiMemory memory, List<MemoryEvidenceDTO> evidence) {
        return AiMemoryVO.builder()
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
                .evidence(orEmpty(evidence).stream()
                        .map(item -> AiMemoryEvidenceVO.builder()
                                .kind(item.getKind())
                                .ref(item.getRef())
                                .postId(item.getPostId())
                                .build())
                        .toList())
                .build();
    }

    private static <T> List<T> orEmpty(List<T> value) {
        return value == null ? List.of() : value;
    }
}
