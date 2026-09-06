package com.stellarink.stats.service;

import com.stellarink.common.exception.BusinessException;
import com.stellarink.common.result.Result;
import com.stellarink.stats.feign.PostClient;
import com.stellarink.stats.feign.dto.PostSummary;
import com.stellarink.stats.vo.StatsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private final PostClient postClient;

    @Override
    public StatsVO overview() {
        List<PostSummary> posts = fetchSummaries();

        StatsVO vo = new StatsVO();
        vo.setTotalPosts((long) posts.size());
        vo.setTotalWords(posts.stream().mapToLong(p -> p.getWordCount() == null ? 0 : p.getWordCount()).sum());

        LocalDate today = LocalDate.now();
        vo.setTodayWords(posts.stream()
                .filter(p -> p.getCreatedAt().toLocalDate().equals(today))
                .mapToLong(p -> p.getWordCount() == null ? 0 : p.getWordCount()).sum());

        vo.setStreakDays(streak(posts.stream().map(p -> p.getCreatedAt().toLocalDate()).distinct().toList(), today));

        if (posts.isEmpty()) {
            vo.setNightRatio(0);
        } else {
            long nights = posts.stream()
                    .map(PostSummary::getCreatedAt)
                    .mapToInt(LocalDateTime -> LocalDateTime.getHour())
                    .filter(h -> h >= 22 || h < 5)
                    .count();
            vo.setNightRatio((int) Math.round(nights * 100.0 / posts.size()));
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        posts.stream()
                .map(PostSummary::getTags)
                .forEach(tags -> {
                    if (tags != null && !tags.isBlank()) {
                        for (String tag : tags.split(",")) {
                            String t = tag.trim();
                            if (!t.isEmpty()) counts.merge(t, 1L, Long::sum);
                        }
                    }
                });
        vo.setTagDistribution(counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new StatsVO.TagCount(e.getKey(), e.getValue()))
                .toList());
        return vo;
    }

    /** Feign 调用失败给出可读错误，避免整页 500 堆栈 */
    private List<PostSummary> fetchSummaries() {
        try {
            Result<List<PostSummary>> result = postClient.summary();
            if (result == null || result.getData() == null) {
                throw new BusinessException("文章服务暂时没有回应。");
            }
            return result.getData();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 post-service /internal/posts/summary 失败", e);
            throw new BusinessException("文章服务暂时没有回应，稍后再看写作脉搏。");
        }
    }

    /** 从今天（或昨天）往前数连续写作夜 */
    private int streak(List<LocalDate> dates, LocalDate today) {
        LocalDate cursor = dates.contains(today) ? today : today.minusDays(1);
        if (!dates.contains(cursor)) {
            return 0;
        }
        int streak = 0;
        while (dates.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }
}
