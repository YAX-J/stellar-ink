package com.stellarink.content.stats.service.impl;

import com.stellarink.common.redis.RedisCache;
import com.stellarink.common.redis.RedisUtils;
import com.stellarink.content.cache.ContentCache;
import com.stellarink.content.post.mapper.PostMapper;
import com.stellarink.content.post.pojo.Post;
import com.stellarink.sharedmodel.vo.stats.StatsVO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatsServiceImplTest {

    private final PostMapper postMapper = mock(PostMapper.class);
    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final ContentCache cache = new ContentCache(new RedisCache(redisUtils));
    private final StatsServiceImpl statsService = new StatsServiceImpl(postMapper, cache);

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Post.class);
    }

    @Test
    void shouldCalculateOverviewFromPublishedPosts() {
        LocalDate today = LocalDate.now();
        when(postMapper.selectList(any())).thenReturn(List.of(
                post(100, "Java,AI", LocalDateTime.of(today, LocalTime.of(23, 0))),
                post(200, "Java", LocalDateTime.of(today.minusDays(1), LocalTime.NOON))
        ));

        StatsVO overview = statsService.overview();

        assertThat(overview.getTotalPosts()).isEqualTo(2);
        assertThat(overview.getTotalWords()).isEqualTo(300);
        assertThat(overview.getTodayWords()).isEqualTo(100);
        assertThat(overview.getStreakDays()).isEqualTo(2);
        assertThat(overview.getNightRatio()).isEqualTo(50);
        assertThat(overview.getTagDistribution())
                .extracting(StatsVO.TagCount::getName, StatsVO.TagCount::getCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Java", 2L),
                        org.assertj.core.groups.Tuple.tuple("AI", 1L)
                );
    }

    @Test
    void shouldReturnZeroValuesWhenThereAreNoPosts() {
        when(postMapper.selectList(any())).thenReturn(List.of());

        StatsVO overview = statsService.overview();

        assertThat(overview.getTotalPosts()).isZero();
        assertThat(overview.getTotalWords()).isZero();
        assertThat(overview.getTodayWords()).isZero();
        assertThat(overview.getStreakDays()).isZero();
        assertThat(overview.getNightRatio()).isZero();
        assertThat(overview.getTagDistribution()).isEmpty();
    }

    private Post post(int wordCount, String tags, LocalDateTime createdAt) {
        Post post = new Post();
        post.setWordCount(wordCount);
        post.setTags(tags);
        post.setCreatedAt(createdAt);
        return post;
    }
}
