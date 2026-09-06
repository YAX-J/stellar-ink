package com.stellarink.stats.service.impl;

import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.serviceapi.feign.PostServiceClient;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.post.PostSummaryVO;
import com.stellarink.sharedmodel.vo.stats.StatsVO;
import com.stellarink.stats.service.StatsService;
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

    private final PostServiceClient postServiceClient;

    @Override
    public StatsVO overview() {
        List<PostSummaryVO> posts = fetchSummaries();

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
                    .map(PostSummaryVO::getCreatedAt)
                    .mapToInt(ts -> ts.getHour())
                    .filter(h -> h >= 22 || h < 5)
                    .count();
            vo.setNightRatio((int) Math.round(nights * 100.0 / posts.size()));
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        posts.stream()
                .map(PostSummaryVO::getTags)
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

    /** Feign 调用（resilience4j 断路器 + fallback 兜底），仍失败时给出可读错误 */
    private List<PostSummaryVO> fetchSummaries() {
        try {
            Response<List<PostSummaryVO>> result = postServiceClient.summary();
            if (result == null || result.getData() == null) {
                throw BusinessExceptionHelper.of(ErrorCode.SERVICE_UNAVAILABLE, "文章服务暂时没有回应");
            }
            return result.getData();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 post-service /internal/posts/summary 失败", e);
            throw BusinessExceptionHelper.of(ErrorCode.SERVICE_UNAVAILABLE, "文章服务暂时没有回应，稍后再看写作脉搏。");
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
