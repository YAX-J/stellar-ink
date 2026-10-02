package com.stellarink.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.aiclient.dto.CitationDTO;
import com.stellarink.ai.mapper.AiRetrievalAuditMapper;
import com.stellarink.ai.pojo.AiRetrievalAudit;
import com.stellarink.ai.service.AiRetrievalAuditService;
import com.stellarink.sharedmodel.vo.ai.AiRetrievalAuditSummaryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 检索审计的实现（M8）。
 *
 * <p>三条口径写在 {@link AiRetrievalAuditService} 上，这里只强调最容易做错的一条：
 * <b>写失败不能影响问答</b>。所以 {@code record} 自己吞掉所有异常并只记 warn ——
 * 调用方不需要（也不应该）为审计写 try/catch，那样早晚会有人漏掉一处，
 * 而表现是「问答偶尔 500，原因是一张审计表」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiRetrievalAuditServiceImpl implements AiRetrievalAuditService {

    /** 一次最多记多少篇命中文章：审计是「看趋势」用的，不是全量流水。 */
    private static final int MAX_POST_IDS = 20;

    private final AiRetrievalAuditMapper auditMapper;

    @Override
    public void record(Long userId, String scene, String question, List<CitationDTO> citations,
                       int candidates, boolean failed, Integer latencyMs, String model) {
        try {
            List<CitationDTO> items = citations == null ? List.of() : citations;
            AiRetrievalAudit row = new AiRetrievalAudit();
            row.setTraceId(org.slf4j.MDC.get("traceId") == null ? "" : org.slf4j.MDC.get("traceId"));
            row.setUserId(userId);
            // 场景缺失时记 `unknown` 而**不是**让整条记录落不进去：
            // 「有一次不知道从哪来的检索」本身也是有用的信号，静默丢数据比记个 unknown 危险
            row.setScene(scene == null || scene.isBlank() ? "unknown" : scene);
            row.setStrategy("");
            row.setQuestionHash(sha256(question == null ? "" : question));
            row.setQuestionChars(question == null ? 0 : question.length());
            row.setCandidates(candidates);
            row.setCitations(items.size());
            row.setPostIds(joinPostIds(items));
            row.setTopScore(topScore(items));
            // 拒答 = 有答案路径但一条引用都没有；失败是另一回事（failed 单独记）
            row.setRefused(!failed && items.isEmpty());
            row.setFailed(failed);
            row.setLatencyMs(latencyMs == null ? 0 : latencyMs);
            row.setModel(model == null ? "" : model);
            row.setCreatedAt(LocalDateTime.now());
            auditMapper.insert(row);
        } catch (RuntimeException error) {
            // best-effort：**审计写不进去，绝不能让用户拿不到答案**
            log.warn("检索审计写入失败（不影响本次问答）：scene={} cause={}", scene, error.toString());
        }
    }

    @Override
    public AiRetrievalAuditSummaryVO summarize(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<AiRetrievalAudit> rows = auditMapper.selectList(
                new LambdaQueryWrapper<AiRetrievalAudit>().ge(AiRetrievalAudit::getCreatedAt, since));

        int total = rows.size();
        int refused = (int) rows.stream().filter(row -> Boolean.TRUE.equals(row.getRefused())).count();
        int failed = (int) rows.stream().filter(row -> Boolean.TRUE.equals(row.getFailed())).count();

        Map<Long, Integer> postCounts = new java.util.HashMap<>();
        Map<String, Integer> questionCounts = new java.util.HashMap<>();
        for (AiRetrievalAudit row : rows) {
            for (String raw : splitPostIds(row.getPostIds())) {
                Long postId = parsePostId(raw);
                if (postId != null) {
                    postCounts.merge(postId, 1, Integer::sum);
                }
            }
            questionCounts.merge(row.getQuestionHash(), 1, Integer::sum);
        }

        Map<Long, Integer> topPosts = new LinkedHashMap<>();
        postCounts.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(10)
                .forEach(entry -> topPosts.put(entry.getKey(), entry.getValue()));

        Map<String, Integer> repeated = new TreeMap<>();
        questionCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(10)
                .forEach(entry -> repeated.put(entry.getKey(), entry.getValue()));

        List<String> notes = new ArrayList<>();
        if (total == 0) {
            notes.add("这段时间没有检索记录（要么还没人问，要么审计表还没建）。");
        }
        if (refused > 0) {
            notes.add(
                    String.format("%d 次拒答（%.1f%%）—— 拒答率高说明语料没覆盖，不是链路坏了。",
                            refused, ratio(refused, total) * 100));
        }
        if (failed > 0) {
            notes.add(
                    String.format("%d 次失败（%.1f%%）—— 失败与拒答要分开看：前者是链路问题。",
                            failed, ratio(failed, total) * 100));
        }
        if (!repeated.isEmpty()) {
            notes.add("被反复问的问题（哈希相同）值得补成文章 —— 那是最直接的知识缺口信号。");
        }
        return AiRetrievalAuditSummaryVO.builder()
                .days(days)
                .total(total)
                .refused(refused)
                .failed(failed)
                .refusalRate(ratio(refused, total))
                .failureRate(ratio(failed, total))
                .topPosts(topPosts)
                .repeatQuestions(repeated)
                .notes(notes)
                .build();
    }

    private static double ratio(int part, int whole) {
        return whole == 0 ? 0.0 : BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(whole), 3, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static String joinPostIds(List<CitationDTO> citations) {
        List<String> ids = new ArrayList<>();
        for (CitationDTO item : citations) {
            if (item.getPostId() == null) {
                continue;
            }
            String value = String.valueOf(item.getPostId());
            if (!ids.contains(value)) {
                ids.add(value);
            }
            if (ids.size() >= MAX_POST_IDS) {
                break;
            }
        }
        return String.join(",", ids);
    }

    /** 无法解析的 id 直接跳过：审计里的脏数据不该让汇总整个失败。 */
    private static Long parsePostId(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static List<String> splitPostIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(","));
    }

    private static BigDecimal topScore(List<CitationDTO> citations) {
        return citations.stream()
                .map(CitationDTO::getScore)
                .filter(java.util.Objects::nonNull)
                .max(Double::compareTo)
                .map(value -> BigDecimal.valueOf(value).setScale(5, RoundingMode.HALF_UP))
                .orElse(null);
    }

    /** 问题哈希：存哈希不存原文（问题里可能含个人信息）。 */
    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte item : hashed) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            // SHA-256 在任何 JVM 上都有；真没有的话宁可记空串，也不要让审计拖垮问答
            log.warn("SHA-256 不可用，审计里的问题哈希记空：{}", error.toString());
            return "";
        }
    }
}
