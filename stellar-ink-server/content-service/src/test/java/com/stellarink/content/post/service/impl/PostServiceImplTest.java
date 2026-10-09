package com.stellarink.content.post.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.redis.RedisCache;
import com.stellarink.common.redis.RedisUtils;
import com.stellarink.content.cache.ContentCache;
import com.stellarink.content.comment.mapper.CommentMapper;
import com.stellarink.content.post.mapper.PostGlowMapper;
import com.stellarink.content.post.mapper.PostMapper;
import com.stellarink.content.post.mapper.PostViewMapper;
import com.stellarink.content.post.pojo.Post;
import com.stellarink.sharedmodel.dto.post.PostUpdateDTO;
import com.stellarink.sharedmodel.dto.post.PostQueryDTO;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.vo.post.PostDetailVO;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostServiceImplTest {

    private final PostMapper postMapper = mock(PostMapper.class);
    private final PostGlowMapper postGlowMapper = mock(PostGlowMapper.class);
    private final PostViewMapper postViewMapper = mock(PostViewMapper.class);
    private final CommentMapper commentMapper = mock(CommentMapper.class);
    private final RedisUtils redisUtils = mock(RedisUtils.class);

    /** 真的注册表（而不是 mock）：浏览/点赞的指标断言要读它的实际计数 */
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ContentCache cache = new ContentCache(new RedisCache(redisUtils, meterRegistry));
    private final PostServiceImpl postService = new PostServiceImpl(
            postMapper, postGlowMapper, postViewMapper, commentMapper, cache, meterRegistry);

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Post.class);
    }

    @Test
    void shouldHideDraftFromAnonymousVisitor() {
        when(postMapper.selectById(1L)).thenReturn(post(1L, 10L, 0));

        try (MockedStatic<StpUtil> token = Mockito.mockStatic(StpUtil.class)) {
            token.when(StpUtil::isLogin).thenReturn(false);

            assertThatThrownBy(() -> postService.detail(1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.NOT_FOUND.getCode());
        }
        verify(redisUtils, never()).set(anyString(), any(), any());
    }

    @Test
    void shouldReturnCachedPublicDetailWithoutReadingPost() {
        PostDetailVO cached = new PostDetailVO();
        cached.setId(1L);
        cached.setTitle("缓存中的星");
        when(redisUtils.get(anyString(), eq(PostDetailVO.class))).thenReturn(cached);

        try (MockedStatic<StpUtil> token = Mockito.mockStatic(StpUtil.class)) {
            token.when(StpUtil::isLogin).thenReturn(false);

            org.assertj.core.api.Assertions.assertThat(postService.detail(1L).getTitle())
                    .isEqualTo("缓存中的星");
        }
        verify(postMapper, never()).selectById(1L);
    }

    @Test
    void shouldForbidAuthorUpdatingOthersPost() {
        when(postMapper.selectById(1L)).thenReturn(post(1L, 10L, 0));

        try (MockedStatic<StpUtil> token = Mockito.mockStatic(StpUtil.class);
             MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            token.when(StpUtil::isLogin).thenReturn(true);
            auth.when(AuthHelper::currentRole).thenReturn(Role.AUTHOR);
            auth.when(AuthHelper::loginId).thenReturn(99L);

            assertThatThrownBy(() -> postService.update(1L, new PostUpdateDTO()))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.FORBIDDEN.getCode());
            verify(postMapper, never()).updateById(any(Post.class));
        }
    }

    @Test
    void shouldAllowAdminUpdatingOthersPost() {
        when(postMapper.selectById(1L)).thenReturn(post(1L, 10L, 0));

        try (MockedStatic<StpUtil> token = Mockito.mockStatic(StpUtil.class);
             MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            token.when(StpUtil::isLogin).thenReturn(true);
            auth.when(AuthHelper::currentRole).thenReturn(Role.ADMIN);
            auth.when(AuthHelper::loginId).thenReturn(99L);

            postService.update(1L, new PostUpdateDTO());

            verify(postMapper).updateById(any(Post.class));
            verify(redisUtils).increment("stellar-ink:content:cache:version:post", 1L);
        }
    }

    @Test
    void shouldAllowAuthorUpdatingOwnPost() {
        when(postMapper.selectById(1L)).thenReturn(post(1L, 10L, 0));

        try (MockedStatic<StpUtil> token = Mockito.mockStatic(StpUtil.class);
             MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            token.when(StpUtil::isLogin).thenReturn(true);
            auth.when(AuthHelper::currentRole).thenReturn(Role.AUTHOR);
            auth.when(AuthHelper::loginId).thenReturn(10L);

            postService.update(1L, new PostUpdateDTO());

            verify(postMapper).updateById(any(Post.class));
        }
    }

    @Test
    void shouldUseExactCsvMemberMatchForTagFilter() {
        when(postMapper.selectPage(
                Mockito.<Page<Post>>any(), Mockito.<LambdaQueryWrapper<Post>>any()))
                .thenAnswer(invocation -> {
                    LambdaQueryWrapper<Post> wrapper = invocation.getArgument(1);
                    assertThat(wrapper.getSqlSegment()).contains("FIND_IN_SET");
                    assertThat(wrapper.getParamNameValuePairs().values()).contains("java");
                    Page<Post> result = new Page<>(1, 10);
                    result.setRecords(List.of());
                    return result;
                });
        PostQueryDTO query = new PostQueryDTO();
        query.setTag(" java ");

        assertThat(postService.page(query).getRecords()).isEmpty();
    }

    @Test
    void shouldIncrementViewOnlyAfterWinningDailyGate() {
        when(postMapper.selectById(1L)).thenReturn(post(1L, 10L, 1));
        when(postViewMapper.claimToday(99L)).thenReturn(true);

        try (MockedStatic<StpUtil> token = Mockito.mockStatic(StpUtil.class);
             MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            token.when(StpUtil::isLogin).thenReturn(true);
            auth.when(AuthHelper::loginId).thenReturn(99L);

            assertThat(postService.recordView(1L)).isTrue();
        }

        verify(postMapper).update(Mockito.<Post>isNull(), any());
    }

    /**
     * 指标是这次可观测性改造的交付物之一，所以它必须被断言，而不只是「编译通过」。
     *
     * <p>四档结果各自要能被看见，理由在 PostServiceImpl 的字段注释里：
     * 「去重率突然变成 0」与「重复点赞突然变多」都是**数据库里看不出异常**的问题 ——
     * 数据是对的，只有这两个比例能暴露行为变化。
     */
    @Test
    void shouldExposeViewDedupAndGlowDuplicateAsMetrics() {
        when(postMapper.selectById(1L)).thenReturn(post(1L, 10L, 1));

        try (MockedStatic<StpUtil> token = Mockito.mockStatic(StpUtil.class);
             MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            token.when(StpUtil::isLogin).thenReturn(true);
            auth.when(AuthHelper::loginId).thenReturn(99L);

            // 浏览：第一次闸门放行（counted），第二次同一人当天已计过（deduped）
            when(postViewMapper.claimToday(99L)).thenReturn(true);
            assertThat(postService.recordView(1L)).isTrue();
            when(postViewMapper.claimToday(99L)).thenReturn(false);
            assertThat(postService.recordView(1L)).isFalse();

            // 点赞：唯一键里没有记录 → 真的加了一赞（created）
            when(postGlowMapper.selectCount(any())).thenReturn(0L);
            postService.glow(1L);
        }

        assertThat(counter("stellar.view.recorded", "counted")).isEqualTo(1d);
        assertThat(counter("stellar.view.recorded", "deduped")).isEqualTo(1d);
        assertThat(meterRegistry.get("stellar.view.recorded")
                .tag("kind", "post").tag("result", "counted").counter().count())
                .as("文章与笔记共用同一个指标名，必须能按 kind 分开")
                .isEqualTo(1d);
        assertThat(counter("stellar.glow.recorded", "created")).isEqualTo(1d);
        // 用 find 而不是 get：Micrometer 只在该计量器被自增过之后才创建它，
        // 「计数为 0」在这里的表现是「计量器不存在」，用 get 会抛 MeterNotFoundException。
        assertThat(meterRegistry.find("stellar.glow.recorded").tag("result", "duplicate").counter())
                .as("这次没有重复点赞，duplicate 不该被创建")
                .isNull();
    }

    private double counter(String metricName, String result) {
        return meterRegistry.get(metricName).tag("result", result).counter().count();
    }

    private Post post(Long id, Long userId, int status) {
        LocalDateTime now = LocalDateTime.now();
        Post post = new Post();
        post.setId(id);
        post.setUserId(userId);
        post.setTitle("尚未发射的星");
        post.setContent("");
        post.setTags("");
        post.setWordCount(0);
        post.setStatus(status);
        post.setGlow(0);
        post.setViewCount(0);
        post.setCreatedAt(now);
        post.setUpdatedAt(now);
        return post;
    }
}
