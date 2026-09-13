package com.stellarink.content.stats.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.content.post.mapper.PostMapper;
import com.stellarink.content.post.pojo.Post;
import com.stellarink.content.stats.service.StatsService;
import com.stellarink.sharedmodel.vo.stats.StatsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private final PostMapper postMapper;

    @Override
    public StatsVO overview() {
        List<Post> posts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, 1)
                .select(Post::getWordCount, Post::getTags, Post::getCreatedAt));

        StatsVO vo = new StatsVO();
        vo.setTotalPosts((long) posts.size());
        vo.setTotalWords(posts.stream().mapToLong(this::wordCount).sum());

        LocalDate today = LocalDate.now();
        vo.setTodayWords(posts.stream()
                .filter(p -> p.getCreatedAt().toLocalDate().equals(today))
                .mapToLong(this::wordCount)
                .sum());

        Set<LocalDate> writingDates = posts.stream()
                .map(p -> p.getCreatedAt().toLocalDate())
                .collect(Collectors.toSet());
        vo.setStreakDays(streak(writingDates, today));

        long nights = posts.stream()
                .map(Post::getCreatedAt)
                .mapToInt(ts -> ts.getHour())
                .filter(hour -> hour >= 22 || hour < 5)
                .count();
        vo.setNightRatio(posts.isEmpty() ? 0 : (int) Math.round(nights * 100.0 / posts.size()));

        Map<String, Long> counts = new LinkedHashMap<>();
        posts.stream()
                .map(Post::getTags)
                .filter(tags -> tags != null && !tags.isBlank())
                .forEach(tags -> {
                    for (String tag : tags.split(",")) {
                        String normalized = tag.trim();
                        if (!normalized.isEmpty()) {
                            counts.merge(normalized, 1L, Long::sum);
                        }
                    }
                });
        vo.setTagDistribution(counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> new StatsVO.TagCount(entry.getKey(), entry.getValue()))
                .toList());
        return vo;
    }

    private long wordCount(Post post) {
        return post.getWordCount() == null ? 0 : post.getWordCount();
    }

    /** 从今天（或昨天）往前数连续写作夜。 */
    private int streak(Set<LocalDate> dates, LocalDate today) {
        LocalDate cursor = dates.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (dates.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }
}
