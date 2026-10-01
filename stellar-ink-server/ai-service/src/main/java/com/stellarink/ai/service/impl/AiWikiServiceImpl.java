package com.stellarink.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.AiWikiClaimDTO;
import com.stellarink.aiclient.dto.AiWikiClaimsRequestDTO;
import com.stellarink.aiclient.dto.AiWikiClaimsResultDTO;
import com.stellarink.ai.enums.AiCallScene;
import com.stellarink.ai.mapper.AiWikiClaimMapper;
import com.stellarink.ai.pojo.AiWikiClaim;
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.ai.service.AiWikiService;
import com.stellarink.sharedmodel.vo.ai.AiWikiBuildVO;
import com.stellarink.sharedmodel.vo.ai.AiWikiClaimVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

        List<String> notes = new ArrayList<>(nullSafe(result.getNotes()));
        notes.add("落库：新增 " + counters.inserted + " 条、更新 " + counters.updated
                + " 条、未变动 " + counters.skipped + " 条。");
        if (counters.rejected > 0) {
            // 缺证据字段的记录**不写**：写进去就是一条无法核验的「知识」，要把这件事说出来
            notes.add("⚠️ " + counters.rejected + " 条主张缺证据字段（postId/contentHash/text），"
                    + "已拒绝落库 —— 它们无法回到原文。");
        }
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
                .entities(orZero(stats == null ? null : stats.getEntities()))
                .entityProposed(orZero(stats == null ? null : stats.getEntityProposed()))
                .entityKept(orZero(stats == null ? null : stats.getEntityKept()))
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

    /** 一轮落库的三种结果：新增 / 更新 / 未变动（外加「缺证据被拒」）。 */
    private static final class Counters {
        private int inserted;
        private int updated;
        private int skipped;
        private int rejected;
    }
}
