package com.stellarink.content.post.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.vo.post.PostDetailVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.time.LocalDateTime;

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
    private final ContentCache cache = new ContentCache(new RedisCache(redisUtils));
    private final PostServiceImpl postService = new PostServiceImpl(
            postMapper, postGlowMapper, postViewMapper, commentMapper, cache);

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
