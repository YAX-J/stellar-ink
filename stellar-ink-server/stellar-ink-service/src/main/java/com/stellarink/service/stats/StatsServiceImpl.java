package com.stellarink.service.stats;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.dao.entity.PostEntity;
import com.stellarink.dao.mapper.PostMapper;
import com.stellarink.domain.enums.PostStatus;
import com.stellarink.domain.vo.StatsVO;
import com.stellarink.domain.vo.TagVO;
import com.stellarink.service.post.PostServiceImpl;
import com.stellarink.service.stats.StatsService;
import com.stellarink.service.tag.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private final PostMapper postMapper;
    private final TagService tagService;

    @Override
    public StatsVO overview() {
        List<PostEntity> posts = postMapper.selectList(new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getStatus, PostStatus.PUBLISHED.getValue()));

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
                    .map(PostEntity::getCreatedAt)
                    .mapToInt(LocalDateTime::getHour)
                    .filter(h -> h >= 22 || h < 5)
                    .count();
            vo.setNightRatio((int) Math.round(nights * 100.0 / posts.size()));
        }
        vo.setTagDistribution(tagService.listWithCount().stream()
                .sorted(Comparator.comparingLong(TagVO::getCount).reversed())
                .toList());
        return vo;
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
