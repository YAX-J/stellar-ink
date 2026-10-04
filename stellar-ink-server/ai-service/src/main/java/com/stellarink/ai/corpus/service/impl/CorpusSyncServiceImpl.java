package com.stellarink.ai.corpus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.ai.corpus.mapper.AiContentSnapshotMapper;
import com.stellarink.ai.corpus.pojo.AiContentSnapshot;
import com.stellarink.ai.corpus.service.CorpusSyncService;
import com.stellarink.contentclient.client.ContentCorpusClient;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.corpus.CorpusContentVO;
import com.stellarink.sharedmodel.vo.corpus.CorpusItemVO;
import com.stellarink.sharedmodel.vo.corpus.CorpusSliceVO;
import com.stellarink.sharedmodel.vo.corpus.CorpusSyncResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 语料投影同步实现。
 *
 * <p>一轮同步 = 「全量拉清单 → 逐条比对 → 写入变更 → 删除上游不再返回的行」。
 *
 * <p>⚠️ 为什么是**全量清单**而不是只拉 `since` 之后的增量：增量发现不了「上游删掉了什么」，
 * 而下架 / 删除 / **笔记转私有**都表现为「上游不再返回它」。漏删的后果是私有内容继续被问答引用，
 * 属于不能接受的一类；而全量清单只有 id + 哈希（几十到几千行），代价可以忽略。
 *
 * <p>⚠️ 为什么删除不做「防误删阈值」：**隐私优先于可恢复成本**。
 * 上游返回空就是空（真的没有公开内容了），此时必须删干净；
 * 万一那是一次上游 bug，代价是重新嵌入（可恢复、花钱），而漏删是泄漏（不可恢复）。
 * 只在删除量异常大时打一条 WARN，让人能在日志里看见这个过程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorpusSyncServiceImpl implements CorpusSyncService {

    /** 单页拉取上限（content-service 侧夹到 1000，这里取它的上限以免多跑一轮）。 */
    private static final int PAGE_LIMIT = 1000;

    /** 翻页保护：正常语料远小于此；到了这个轮数说明上游的游标没推进，必须停下而不是死循环。 */
    private static final int MAX_PAGES = 100;

    private final ContentCorpusClient contentCorpusClient;
    private final AiContentSnapshotMapper snapshotMapper;

    @Override
    public CorpusSyncResultVO sync() {
        LocalDateTime startedAt = LocalDateTime.now();
        CorpusSyncResultVO result = new CorpusSyncResultVO();
        result.setSyncedAt(startedAt);

        List<CorpusItemVO> upstream;
        try {
            upstream = fetchAll();
        } catch (RuntimeException error) {
            // 拉取失败：一行都不动。宁可这一轮什么都不做，也不能因为一次抖动把知识库清空。
            return failed(result, "拉取语料失败", error);
        }

        result.setUpstream(upstream.size());

        List<AiContentSnapshot> local = snapshotMapper.selectList(new LambdaQueryWrapper<>());
        Set<String> upstreamKeys = new HashSet<>();
        List<AiContentSnapshot> toInsert = new ArrayList<>();
        List<AiContentSnapshot> toUpdate = new ArrayList<>();
        int unchanged = 0;
        int skipped = 0;

        // 整个比对循环都要兜住：**回取正文**也是一次跨服务调用，它的传输失败同样是「这一轮什么都别做」，
        // 而不是把异常抛给调用方（管理端点会变成 500，定时任务只剩一行日志）。写入都在循环之后，
        // 所以在这里中止不会留下半成品。
        try {
            for (CorpusItemVO item : upstream) {
                String key = key(item.getKind() == null ? null : item.getKind().key(), item.getId());
                if (key == null) {
                    continue;
                }
                AiContentSnapshot existing = local.stream()
                        .filter(row -> key.equals(key(row.getKind(), row.getContentId())))
                        .findFirst()
                        .orElse(null);
                boolean changed = existing == null || !existing.getDocHash().equals(item.getDocHash());

                // 正文只在**新增/变更**时回取：docHash 没变就说明上游那篇一字未动，沿用已存的正文即可
                //（否则每轮都要把整库正文搬一遍，而语料之后可能上千篇）。
                String content = null;
                if (changed) {
                    content = fetchContent(item);
                    if (content == null) {
                        // 清单里有、正文却取不到 = 上游认为它已不可公开（典型并发：刚转私有 / 刚下架）。
                        // **从严处理**：不把它算进 upstreamKeys —— 删除侧于是会清掉旧行。
                        // 绝不能沿用旧正文，那正是「私有内容继续被问答引用」的成因。
                    skipped++;
                    continue;
                }
            }

            upstreamKeys.add(key);
            if (existing == null) {
                toInsert.add(newRow(item, content, startedAt));
                continue;
            }
            if (changed) {
                existing.setTitle(item.getTitle());
                existing.setContent(content);
                existing.setDocHash(item.getDocHash());
                existing.setUpdatedAt(item.getUpdatedAt());
                existing.setSyncedAt(startedAt);
                toUpdate.add(existing);
                continue;
            }
            unchanged++;
            }
        } catch (RuntimeException error) {
            return failed(result, "回取正文失败", error);
        }

        // 上游不再返回的行 = 下架 / 已删 / 转为私有 → 必须删掉，否则还会被问答引用
        List<Long> toDelete = local.stream()
                .filter(row -> !upstreamKeys.contains(key(row.getKind(), row.getContentId())))
                .map(AiContentSnapshot::getId)
                .toList();

        toInsert.forEach(snapshotMapper::insert);
        toUpdate.forEach(snapshotMapper::updateById);
        if (!toDelete.isEmpty()) {
            snapshotMapper.deleteByIds(toDelete);
            int remaining = local.size() - toDelete.size();
            if (remaining > 0 && toDelete.size() * 2 > local.size()) {
                // 一次删掉超过一半：通常是上游真的清空/大面积下架，也可能是上游出问题。
                // 不阻止（隐私优先），但要让人在日志里看见，而不是事后才发现知识库空了。
                log.warn("语料同步删除了 {} 行（原有 {} 行，占一多半）——上游是否大面积下架？",
                        toDelete.size(), local.size());
            }
        }

        result.setInserted(toInsert.size());
        result.setUpdated(toUpdate.size());
        result.setUnchanged(unchanged);
        result.setSkipped(skipped);
        result.setRemoved(toDelete.size());
        result.setTotal(snapshotSize());
        log.info("语料同步完成：上游 {} 条 → 新增 {}、更新 {}、未变 {}、跳过 {}、删除 {}，表现有 {} 行",
                result.getUpstream(), result.getInserted(), result.getUpdated(),
                result.getUnchanged(), result.getSkipped(), result.getRemoved(), result.getTotal());
        return result;
    }

    @Override
    public int snapshotSize() {
        return Math.toIntExact(snapshotMapper.selectCount(new LambdaQueryWrapper<>()));
    }

    /**
     * 统一的「这一轮失败」出口：**不做任何写入与删除**，只把原因如实回给调用方。
     *
     * <p>抽出来是因为有两条失败路径（拉清单、回取正文），而它们的语义必须完全一致 ——
     * 任何一处写成「抛出去」或「删一半」，都会让一次网络抖动变成知识库缺内容或清空。
     */
    private CorpusSyncResultVO failed(CorpusSyncResultVO result, String stage, RuntimeException error) {
        result.setFailed(true);
        result.setReason(stage + "：" + error.getMessage());
        result.setTotal(snapshotSize());
        log.warn("语料同步失败于「{}」（本轮不做任何删除，表保持原样）：{}", stage, error.toString());
        return result;
    }

    /** 翻页拉全量清单（清单不含正文，所以可以放心一次拉 1000 条）。 */
    private List<CorpusItemVO> fetchAll() {
        List<CorpusItemVO> all = new ArrayList<>();
        LocalDateTime cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Response<CorpusSliceVO> response = contentCorpusClient.slice(cursor, null, PAGE_LIMIT);
            CorpusSliceVO slice = response == null ? null : response.getData();
            if (slice == null || slice.getItems() == null || slice.getItems().isEmpty()) {
                return all;
            }
            all.addAll(slice.getItems());
            if (!Boolean.TRUE.equals(slice.getTruncated())) {
                return all;
            }
            LocalDateTime next = slice.getMaxUpdatedAt();
            if (next == null || next.equals(cursor)) {
                // 游标没推进：再拉就是同一页。停下并留痕，别死循环
                log.warn("语料清单翻页游标未推进（page={}，cursor={}），提前结束本轮拉取", page, cursor);
                return all;
            }
            cursor = next;
        }
        log.warn("语料清单翻页超过 {} 页，提前结束本轮拉取（已取 {} 条）", MAX_PAGES, all.size());
        return all;
    }

    /**
     * 回取单篇正文。
     *
     * @return 正文；**null = 上游说这篇已不可公开**（404 语义）。
     * @throws RuntimeException 传输层失败（连不上/超时/5xx）—— 必须向上抛：
     *         把它当成「不可公开」会让一次网络抖动变成「删掉一整批语料」。
     */
    private String fetchContent(CorpusItemVO item) {
        Response<CorpusContentVO> response =
                contentCorpusClient.content(item.getKind().key(), item.getId());
        if (response == null) {
            return null;
        }
        // ⚠️ 必须用 equals：ErrorCode.getCode() 是 Integer，404 超出 Integer 缓存区间（-128..127），
        // 用 `==` 比较装箱值**永远为假** —— 那这条分支与它的告警日志就永远不会生效。
        if (ErrorCode.NOT_FOUND.getCode().equals(response.getCode())) {
            log.warn("清单里有 {}:{} 但正文已不可公开（多半是并发修改），本轮跳过并清理旧行",
                    item.getKind().key(), item.getId());
            return null;
        }
        CorpusContentVO data = response.getData();
        return data == null ? null : data.getContent();
    }

    private AiContentSnapshot newRow(CorpusItemVO item, String content, LocalDateTime syncedAt) {
        AiContentSnapshot row = new AiContentSnapshot();
        row.setKind(item.getKind().key());
        row.setContentId(item.getId());
        row.setTitle(item.getTitle());
        row.setContent(content);
        row.setDocHash(item.getDocHash());
        row.setWordCount(0);
        row.setUpdatedAt(item.getUpdatedAt());
        row.setSyncedAt(syncedAt);
        return row;
    }

    private String key(String kind, Long contentId) {
        if (kind == null || contentId == null) {
            return null;
        }
        return kind + ":" + contentId;
    }
}
