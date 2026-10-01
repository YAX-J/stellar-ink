package com.stellarink.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.AiWikiClaimDTO;
import com.stellarink.aiclient.dto.AiWikiClaimsRequestDTO;
import com.stellarink.aiclient.dto.AiWikiClaimsResultDTO;
import com.stellarink.aiclient.dto.AiWikiEntityDTO;
import com.stellarink.aiclient.dto.AiWikiRelationDTO;
import com.stellarink.ai.enums.AiCallScene;
import com.stellarink.ai.mapper.AiWikiClaimMapper;
import com.stellarink.ai.mapper.AiWikiEntityMapper;
import com.stellarink.ai.mapper.AiWikiEntityMentionMapper;
import com.stellarink.ai.mapper.AiWikiRelationEvidenceMapper;
import com.stellarink.ai.mapper.AiWikiRelationMapper;
import com.stellarink.ai.pojo.AiWikiClaim;
import com.stellarink.ai.pojo.AiWikiEntity;
import com.stellarink.ai.pojo.AiWikiEntityMention;
import com.stellarink.ai.pojo.AiWikiRelation;
import com.stellarink.ai.pojo.AiWikiRelationEvidence;
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.ai.service.AiWikiService;
import com.stellarink.sharedmodel.vo.ai.AiWikiBuildVO;
import com.stellarink.sharedmodel.vo.ai.AiWikiClaimVO;
import com.stellarink.sharedmodel.vo.ai.AiWikiEntityVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wiki 主张的落库与读取（E4-2）。
 *
 * <p><b>幂等锚点是 (postId, contentHash, claimText)</b>，与表上的唯一键一致。
 * 重复构建是常态（新增文章、换模型、重跑一次），所以「写进去」必须分成三种结果分别计数：
 * 新增 / 更新 / 未变动 —— 只回「新增 N 条」的话，第二次构建会显示「又新增了 N 条」，
 * 让人以为知识库在膨胀，其实只是同一批主张被重写了一遍。
 *
 * <p>置信度与章节路径属于「可能变、但不改变主张本身」的字段：命中锚点时**只在真的不同才更新**，
 * 否则每次构建都会产生一批无意义的写操作与 `updated_at` 变动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiWikiServiceImpl implements AiWikiService {

    private final PythonAiClient pythonAiClient;

    private final AiWikiClaimMapper claimMapper;

    private final AiWikiEntityMapper entityMapper;

    private final AiWikiEntityMentionMapper mentionMapper;

    private final AiWikiRelationMapper relationMapper;

    private final AiWikiRelationEvidenceMapper relationEvidenceMapper;

    private final AiUsageService usageService;

    @Override
    public AiWikiBuildVO build(AiWikiClaimsRequestDTO request) {
        // 走调用账（E3-1/E3-2）：Wiki 构建是**批量模型调用**，比一次问答贵得多，
        // 更该记清谁跑了多少篇、花了多少 token、有没有被配额挡住
        AiWikiClaimsResultDTO result = usageService.around(
                AiCallScene.WIKI,
                () -> pythonAiClient.wikiClaims(request),
                null);

        Counters counters = new Counters();
        for (AiWikiClaimDTO claim : nullSafe(result.getClaims())) {
            store(claim, counters);
        }
        // 实体与关系（E4-6）：它们依附在刚写下的主张上，所以必须在主张之后处理
        GraphCounters graph = storeGraph(result);
        counters.entities = graph.entities;
        counters.mentions = graph.mentions;
        counters.relations = graph.relations;

        List<String> notes = new ArrayList<>(nullSafe(result.getNotes()));
        notes.add("落库：新增 " + counters.inserted + " 条、更新 " + counters.updated
                + " 条、未变动 " + counters.skipped + " 条。");
        if (counters.rejected > 0) {
            // 缺证据字段的记录**不写**：写进去就是一条无法核验的「知识」，要把这件事说出来
            notes.add("⚠️ " + counters.rejected + " 条主张缺证据字段（postId/contentHash/text），"
                    + "已拒绝落库 —— 它们无法回到原文。");
        }
        notes.add("知识图：实体 " + counters.entities + " 个、提及 " + counters.mentions
                + " 条、共现关系 " + counters.relations + " 条。");
        AiWikiClaimsResultDTO.AiWikiStatsDTO stats = result.getStats();
        log.info("Wiki 构建完成：抽取 kept={} 落库 inserted={} updated={} skipped={} model={}",
                stats == null ? null : stats.getKept(),
                counters.inserted, counters.updated, counters.skipped, result.getUsageModel());

        // ⚠️ 用 `orZero` 而不是三元里的 `0`：`条件 ? 0 : 某个 Integer` 会触发**拆箱**，
        // 字段缺省时直接 NPE（Python 少回一个字段就会炸 —— 跨版本升级时很容易发生）
        return AiWikiBuildVO.builder()
                .posts(orZero(stats == null ? null : stats.getPosts()))
                .proposed(orZero(stats == null ? null : stats.getProposed()))
                .kept(orZero(stats == null ? null : stats.getKept()))
                .inserted(counters.inserted)
                .updated(counters.updated)
                .skipped(counters.skipped)
                .dropped(stats == null ? Map.of() : orEmpty(stats.getDropped()))
                // ⚠️ 口径要分清：`entities` / `relations` 是**落库侧**的数（真的写进了几个），
                // `entityProposed` / `entityKept` 才是**模型侧**的账（提出多少、通过校验多少）。
                // 混用会让「关系端点实体缺失、一条都没写」显示成「写了 1 条」
                .entities(counters.entities)
                .entityProposed(orZero(stats == null ? null : stats.getEntityProposed()))
                .entityKept(orZero(stats == null ? null : stats.getEntityKept()))
                .relations(counters.relations)
                .topics(orZero(stats == null ? null : stats.getTopics()))
                .usageModel(result.getUsageModel())
                .latencyMs(result.getLatencyMs())
                .notes(notes)
                .build();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static Map<String, Integer> orEmpty(Map<String, Integer> value) {
        return value == null ? Map.of() : value;
    }

    @Override
    public List<AiWikiClaimVO> claimsOfPost(Long postId) {
        if (postId == null || postId < 1) {
            return List.of();
        }
        List<AiWikiClaim> rows = claimMapper.selectList(
                new LambdaQueryWrapper<AiWikiClaim>()
                        .eq(AiWikiClaim::getPostId, postId)
                        // 按段落序号排：读者是「读文章时顺手看主张」，顺序必须与文章一致
                        .orderByAsc(AiWikiClaim::getChunkIndex)
                        .orderByAsc(AiWikiClaim::getId));
        return rows.stream().map(AiWikiServiceImpl::toVo).toList();
    }

    @Override
    public long countOfPost(Long postId) {
        if (postId == null || postId < 1) {
            return 0L;
        }
        return claimMapper.selectCount(
                new LambdaQueryWrapper<AiWikiClaim>().eq(AiWikiClaim::getPostId, postId));
    }

    @Override
    public List<AiWikiEntityVO> entitiesOfPost(Long postId) {
        if (postId == null || postId < 1) {
            return List.of();
        }
        // 本文的提及 → 涉及哪些实体
        List<AiWikiEntityMention> mentions = mentionMapper.selectList(
                new LambdaQueryWrapper<AiWikiEntityMention>()
                        .eq(AiWikiEntityMention::getPostId, postId)
                        .orderByAsc(AiWikiEntityMention::getChunkIndex)
                        .orderByAsc(AiWikiEntityMention::getId));
        if (mentions.isEmpty()) {
            return List.of();
        }
        Map<Long, List<AiWikiEntityMention>> byEntity = mentions.stream()
                .collect(Collectors.groupingBy(AiWikiEntityMention::getEntityId));

        List<AiWikiEntity> entities = entityMapper.selectBatchIds(byEntity.keySet());
        if (entities.isEmpty()) {
            // 提及在、实体却没了：只可能是直接删库造成的孤儿数据。如实记一条 warn，
            // 别静默返回空列表（那会让人以为这篇文章没有实体）
            log.warn("Wiki 提及指向了不存在的实体，已忽略：postId={} entityIds={}",
                    postId, byEntity.keySet());
            return List.of();
        }

        Map<Long, AiWikiRelation> pairs = relationsOf(byEntity.keySet());
        // 关系另一端的名字要一起给出来：读者看的是名字，不是 id。
        // ⚠️ 先把**本文已有**的实体填进去 —— 另一端常常也在这篇文章里（两个概念在同一句里出现），
        // 只查「不在本文里的另一端」会让这种情况的名字是 null（踩过）
        Map<Long, String> nameOf = new HashMap<>();
        entities.forEach(entity -> nameOf.put(entity.getId(), entity.getName()));
        Set<Long> otherIds = new HashSet<>();
        for (AiWikiRelation relation : pairs.values()) {
            otherIds.add(relation.getSourceEntityId());
            otherIds.add(relation.getTargetEntityId());
        }
        otherIds.removeAll(nameOf.keySet());
        if (!otherIds.isEmpty()) {
            entityMapper.selectBatchIds(otherIds)
                    .forEach(other -> nameOf.put(other.getId(), other.getName()));
        }

        Map<Long, List<AiWikiRelationEvidence>> evidenceOf = evidenceOf(pairs.keySet());

        List<AiWikiEntityVO> result = new ArrayList<>();
        for (AiWikiEntity entity : entities) {
            List<AiWikiEntityVO.MentionVO> own = byEntity.getOrDefault(entity.getId(), List.of())
                    .stream()
                    .map(mention -> AiWikiEntityVO.MentionVO.builder()
                            .postId(mention.getPostId())
                            .chunkIndex(mention.getChunkIndex())
                            .claimText(mention.getClaimText())
                            .build())
                    .toList();

            List<AiWikiEntityVO.RelationVO> relations = new ArrayList<>();
            for (AiWikiRelation relation : pairs.values()) {
                Long other = null;
                if (entity.getId().equals(relation.getSourceEntityId())) {
                    other = relation.getTargetEntityId();
                } else if (entity.getId().equals(relation.getTargetEntityId())) {
                    other = relation.getSourceEntityId();
                }
                if (other == null) {
                    continue;
                }
                relations.add(AiWikiEntityVO.RelationVO.builder()
                        .entityId(other)
                        .name(nameOf.get(other))
                        .weight(relation.getWeight())
                        .evidence(evidenceOf.getOrDefault(relation.getId(), List.of()).stream()
                                .map(evidence -> AiWikiEntityVO.MentionVO.builder()
                                        .postId(evidence.getPostId())
                                        .chunkIndex(evidence.getChunkIndex())
                                        .claimText(evidence.getClaimText())
                                        .build())
                                .toList())
                        .build());
            }
            // 权重高的在前；并列按名字，保证顺序确定
            relations.sort(Comparator
                    .comparingInt((AiWikiEntityVO.RelationVO rel) -> orZero(rel.getWeight()))
                    .reversed()
                    .thenComparing(rel -> rel.getName() == null ? "" : rel.getName()));

            result.add(AiWikiEntityVO.builder()
                    .id(entity.getId())
                    .name(entity.getName())
                    .normalized(entity.getNormalized())
                    .kind(entity.getKind())
                    .mentionCount(entity.getMentionCount())
                    .postCount(entity.getPostCount())
                    .mentions(own)
                    .relations(relations)
                    .build());
        }
        result.sort(Comparator
                .comparingInt((AiWikiEntityVO vo) -> vo.getMentions() == null
                        ? 0 : vo.getMentions().size())
                .reversed()
                .thenComparing(vo -> vo.getName() == null ? "" : vo.getName()));
        return result;
    }

    /** 与这批实体相关的（无向）关系，按 id 索引。 */
    private Map<Long, AiWikiRelation> relationsOf(Collection<Long> entityIds) {
        if (entityIds.isEmpty()) {
            return Map.of();
        }
        // ⚠️ 用 `and(...)` 把「任一端命中」括起来：不加括号时 OR 会与其它条件串成
        // 「A 命中 且 B 命中 或 C」这种优先级错误，症状是查出一堆无关的边
        LambdaQueryWrapper<AiWikiRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(inner -> inner.in(AiWikiRelation::getSourceEntityId, entityIds)
                .or()
                .in(AiWikiRelation::getTargetEntityId, entityIds));
        Map<Long, AiWikiRelation> pairs = new LinkedHashMap<>();
        relationMapper.selectList(wrapper)
                .forEach(relation -> pairs.put(relation.getId(), relation));
        return pairs;
    }

    private Map<Long, List<AiWikiRelationEvidence>> evidenceOf(Collection<Long> relationIds) {
        if (relationIds.isEmpty()) {
            return Map.of();
        }
        return relationEvidenceMapper
                .selectList(new LambdaQueryWrapper<AiWikiRelationEvidence>()
                        .in(AiWikiRelationEvidence::getRelationId, relationIds)
                        .orderByAsc(AiWikiRelationEvidence::getPostId)
                        .orderByAsc(AiWikiRelationEvidence::getChunkIndex))
                .stream()
                .collect(Collectors.groupingBy(AiWikiRelationEvidence::getRelationId));
    }

    /**
     * 按幂等锚点写入一条主张：命中就比对、不同才更新。
     *
     * <p>没有走「先删后插」：删掉再插入会让主键变化、`created_at` 丢失，
     * 而「这条主张是什么时候第一次被抽出来的」对排查模型改版的影响有用。
     */
    private void store(AiWikiClaimDTO dto, Counters counters) {
        if (dto.getPostId() == null || dto.getText() == null || dto.getContentHash() == null) {
            // 缺证据字段的记录**不写**：写进去就是一条无法核验的「知识」
            counters.rejected++;
            log.warn("Wiki 主张缺证据字段，未落库：postId={} chunkIndex={}",
                    dto.getPostId(), dto.getChunkIndex());
            return;
        }
        AiWikiClaim existing = claimMapper.selectOne(
                new LambdaQueryWrapper<AiWikiClaim>()
                        .eq(AiWikiClaim::getPostId, dto.getPostId())
                        .eq(AiWikiClaim::getContentHash, dto.getContentHash())
                        .eq(AiWikiClaim::getClaimText, dto.getText()));

        if (existing == null) {
            AiWikiClaim row = new AiWikiClaim();
            row.setPostId(dto.getPostId());
            row.setChunkIndex(dto.getChunkIndex() == null ? 0 : dto.getChunkIndex());
            row.setPostVersion(dto.getPostVersion());
            row.setContentHash(dto.getContentHash());
            row.setClaimText(dto.getText());
            row.setQuote(dto.getQuote());
            row.setHeadingPath(dto.getHeadingPath() == null ? "" : dto.getHeadingPath());
            row.setConfidence(confidenceOf(dto.getConfidence()));
            claimMapper.insert(row);
            counters.inserted++;
            return;
        }

        BigDecimal confidence = confidenceOf(dto.getConfidence());
        String headingPath = dto.getHeadingPath() == null ? "" : dto.getHeadingPath();
        boolean changed = !headingPath.equals(existing.getHeadingPath())
                || existing.getConfidence() == null
                || existing.getConfidence().compareTo(confidence) != 0
                || !Objects.equals(dto.getQuote(), existing.getQuote());
        if (!changed) {
            counters.skipped++;
            return;
        }
        // 用显式更新而不是 updateById：后者忽略 null 字段，
        // 而这里「把 headingPath 更新成空串」是合法变更（章节被删掉了）
        AiWikiClaim update = new AiWikiClaim();
        update.setId(existing.getId());
        update.setHeadingPath(headingPath);
        update.setConfidence(confidence);
        update.setQuote(dto.getQuote());
        update.setPostVersion(dto.getPostVersion());
        claimMapper.updateById(update);
        counters.updated++;
    }

    private static BigDecimal confidenceOf(Double raw) {
        double value = raw == null ? 0.5 : Math.min(1.0, Math.max(0.0, raw));
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }

    private static AiWikiClaimVO toVo(AiWikiClaim row) {
        return AiWikiClaimVO.builder()
                .id(row.getId())
                .postId(row.getPostId())
                .chunkIndex(row.getChunkIndex())
                .postVersion(row.getPostVersion())
                .contentHash(row.getContentHash())
                .text(row.getClaimText())
                .quote(row.getQuote())
                .headingPath(row.getHeadingPath())
                .confidence(row.getConfidence() == null ? null : row.getConfidence().doubleValue())
                .createdAt(row.getCreatedAt())
                .updatedAt(row.getUpdatedAt())
                .build();
    }

    private static <T> List<T> nullSafe(List<T> items) {
        return items == null ? List.of() : items;
    }

    /**
     * 落库实体、提及与共现关系（E4-6）。
     *
     * <p>三条必须保持的口径：
     * ① **实体按 `normalized` 幂等**（写法的差异不是不同实体）；
     * ② **提及的唯一键含 claimText** —— 每个提及回到一句具体主张，而不是「大概在这篇里」；
     * ③ **关系是无向的**：两端按 normalized 排序后再写，否则 (A,B) 与 (B,A) 会各存一行、
     *    权重看起来只有实际的一半。
     *
     * <p>关系的证据**先清后写**（按 relationId 删掉再插）：每次重建都可能多出/少掉一两句证据，
     * 「只增不删」会让旧证据永远留着，而 weight 与证据条数一旦对不上，
     * 这条边就没法用来核对了。
     */
    private GraphCounters storeGraph(AiWikiClaimsResultDTO result) {
        GraphCounters counters = new GraphCounters();
        Map<String, Long> entityIds = new LinkedHashMap<>();

        for (AiWikiEntityDTO entity : nullSafe(result.getEntities())) {
            if (entity.getNormalized() == null || entity.getNormalized().isBlank()) {
                continue;
            }
            Long entityId = upsertEntity(entity);
            entityIds.put(entity.getNormalized(), entityId);
            for (AiWikiEntityDTO.AiWikiEntityMentionDTO mention : nullSafe(entity.getMentions())) {
                if (storeMention(entityId, mention)) {
                    counters.mentions++;
                }
            }
        }
        // 计数按**去重后的实体数**（同一 normalized 写两次是命中，不是两个实体）
        counters.entities = entityIds.size();

        for (AiWikiRelationDTO relation : nullSafe(result.getRelations())) {
            Long sourceId = entityIds.get(relation.getSource());
            Long targetId = entityIds.get(relation.getTarget());
            if (sourceId == null || targetId == null) {
                // 端点实体不在这一批里（被校验丢掉或没写成功）：这条边**不落库**，
                // 否则图上会出现指向不存在实体的连线
                log.warn("Wiki 关系缺少端点实体，已跳过：{} → {}",
                        relation.getSource(), relation.getTarget());
                continue;
            }
            counters.relations += upsertRelation(sourceId, targetId, relation) ? 1 : 0;
        }
        return counters;
    }

    private Long upsertEntity(AiWikiEntityDTO dto) {
        AiWikiEntity existing = entityMapper.selectOne(
                new LambdaQueryWrapper<AiWikiEntity>()
                        .eq(AiWikiEntity::getNormalized, dto.getNormalized()));
        int mentions = dto.getCount() == null ? 0 : dto.getCount();
        int posts = dto.getPostIds() == null ? 0 : dto.getPostIds().size();
        String kind = dto.getKind() == null || dto.getKind().isBlank() ? "other" : dto.getKind();

        if (existing == null) {
            AiWikiEntity row = new AiWikiEntity();
            row.setNormalized(dto.getNormalized());
            row.setName(dto.getName());
            row.setKind(kind);
            row.setMentionCount(mentions);
            row.setPostCount(posts);
            entityMapper.insert(row);
            return row.getId();
        }
        // 计数字段每次都覆盖（它们是冗余计数，必须跟着明细走）；名字与类型只在变了时更新
        AiWikiEntity update = new AiWikiEntity();
        update.setId(existing.getId());
        update.setMentionCount(mentions);
        update.setPostCount(posts);
        if (dto.getName() != null && !dto.getName().equals(existing.getName())) {
            update.setName(dto.getName());
        }
        if (!kind.equals(existing.getKind())) {
            update.setKind(kind);
        }
        entityMapper.updateById(update);
        return existing.getId();
    }

    /** @return 是否新写了一条提及（命中唯一键即跳过，用于计数） */
    private boolean storeMention(Long entityId, AiWikiEntityDTO.AiWikiEntityMentionDTO mention) {
        if (mention.getPostId() == null || mention.getClaimText() == null) {
            return false;
        }
        int chunkIndex = mention.getChunkIndex() == null ? 0 : mention.getChunkIndex();
        Long exists = mentionMapper.selectCount(new LambdaQueryWrapper<AiWikiEntityMention>()
                .eq(AiWikiEntityMention::getEntityId, entityId)
                .eq(AiWikiEntityMention::getPostId, mention.getPostId())
                .eq(AiWikiEntityMention::getChunkIndex, chunkIndex)
                .eq(AiWikiEntityMention::getClaimText, mention.getClaimText()));
        if (exists != null && exists > 0) {
            return false;
        }
        AiWikiEntityMention row = new AiWikiEntityMention();
        row.setEntityId(entityId);
        row.setPostId(mention.getPostId());
        row.setChunkIndex(chunkIndex);
        row.setClaimText(mention.getClaimText());
        mentionMapper.insert(row);
        return true;
    }

    /** @return 是否写入了这条关系（端点实体缺失时返回 false，由调用方跳过计数） */
    private boolean upsertRelation(Long sourceId, Long targetId, AiWikiRelationDTO dto) {
        // 无向边只有一种表示：先按 id 排序，避免 (A,B)/(B,A) 各存一行
        Long low = Math.min(sourceId, targetId);
        Long high = Math.max(sourceId, targetId);
        if (low.equals(high)) {
            return false;
        }
        int weight = dto.getWeight() == null ? 1 : dto.getWeight();

        AiWikiRelation existing = relationMapper.selectOne(new LambdaQueryWrapper<AiWikiRelation>()
                .eq(AiWikiRelation::getSourceEntityId, low)
                .eq(AiWikiRelation::getTargetEntityId, high));
        Long relationId;
        if (existing == null) {
            AiWikiRelation row = new AiWikiRelation();
            row.setSourceEntityId(low);
            row.setTargetEntityId(high);
            row.setWeight(weight);
            relationMapper.insert(row);
            relationId = row.getId();
        } else {
            relationId = existing.getId();
            if (existing.getWeight() == null || existing.getWeight() != weight) {
                AiWikiRelation update = new AiWikiRelation();
                update.setId(relationId);
                update.setWeight(weight);
                relationMapper.updateById(update);
            }
        }

        // 证据先清后写：只增不删会让旧证据永远留着，而 weight 与证据条数一旦对不上，
        // 这条边就没法用来核对了
        relationEvidenceMapper.delete(new LambdaQueryWrapper<AiWikiRelationEvidence>()
                .eq(AiWikiRelationEvidence::getRelationId, relationId));
        for (AiWikiRelationDTO.EvidenceDTO evidence : nullSafe(dto.getEvidence())) {
            if (evidence.getPostId() == null || evidence.getClaimText() == null) {
                continue;
            }
            AiWikiRelationEvidence row = new AiWikiRelationEvidence();
            row.setRelationId(relationId);
            row.setPostId(evidence.getPostId());
            row.setChunkIndex(evidence.getChunkIndex() == null ? 0 : evidence.getChunkIndex());
            row.setClaimText(evidence.getClaimText());
            relationEvidenceMapper.insert(row);
        }
        return true;
    }

    /** 一轮知识图落库的计数：实体 / 提及 / 关系。 */
    private static final class GraphCounters {
        private int entities;
        private int mentions;
        private int relations;
    }

    /** 一轮落库的三种结果：新增 / 更新 / 未变动（外加「缺证据被拒」与知识图三计数）。 */
    private static final class Counters {
        private int inserted;
        private int updated;
        private int skipped;
        private int rejected;
        private int entities;
        private int mentions;
        private int relations;
    }
}
