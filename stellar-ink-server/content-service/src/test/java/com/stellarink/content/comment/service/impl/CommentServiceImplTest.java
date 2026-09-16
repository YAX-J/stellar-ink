package com.stellarink.content.comment.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.redis.RedisCache;
import com.stellarink.common.redis.RedisUtils;
import com.stellarink.content.cache.ContentCache;
import com.stellarink.content.comment.mapper.CommentMapper;
import com.stellarink.content.comment.pojo.Comment;
import com.stellarink.content.post.mapper.PostMapper;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.exception.BusinessException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentServiceImplTest {

    private final CommentMapper commentMapper = mock(CommentMapper.class);
    private final PostMapper postMapper = mock(PostMapper.class);
    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final ContentCache cache = new ContentCache(new RedisCache(redisUtils));
    private final CommentServiceImpl commentService = new CommentServiceImpl(commentMapper, postMapper, cache);

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Comment.class);
    }

    @Test
    void shouldAllowCommentAuthorToDelete() {
        when(commentMapper.selectById(2L)).thenReturn(comment(2L, 1L, 10L));

        try (MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::currentRole).thenReturn(Role.READER);
            auth.when(AuthHelper::loginId).thenReturn(10L);

            commentService.delete(1L, 2L);

            auth.verify(() -> AuthHelper.requireAtLeast(Role.READER));
            verify(commentMapper).update(Mockito.<Comment>isNull(), Mockito.<Wrapper<Comment>>any());
        }
    }

    @Test
    void shouldAllowAdminToDeleteOthersComment() {
        when(commentMapper.selectById(2L)).thenReturn(comment(2L, 1L, 10L));

        try (MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::currentRole).thenReturn(Role.ADMIN);
            auth.when(AuthHelper::loginId).thenReturn(99L);

            commentService.delete(1L, 2L);

            verify(commentMapper).update(Mockito.<Comment>isNull(), Mockito.<Wrapper<Comment>>any());
        }
    }

    @Test
    void shouldForbidReaderDeletingOthersComment() {
        when(commentMapper.selectById(2L)).thenReturn(comment(2L, 1L, 10L));

        try (MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::currentRole).thenReturn(Role.READER);
            auth.when(AuthHelper::loginId).thenReturn(99L);

            assertThatThrownBy(() -> commentService.delete(1L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.FORBIDDEN.getCode());
            verify(commentMapper, never()).update(Mockito.<Comment>isNull(), Mockito.<Wrapper<Comment>>any());
        }
    }

    @Test
    void shouldHideCommentWhenPostPathDoesNotMatch() {
        when(commentMapper.selectById(2L)).thenReturn(comment(2L, 8L, 10L));

        try (MockedStatic<AuthHelper> ignored = Mockito.mockStatic(AuthHelper.class)) {
            assertThatThrownBy(() -> commentService.delete(1L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.NOT_FOUND.getCode());
            verify(commentMapper, never()).update(Mockito.<Comment>isNull(), Mockito.<Wrapper<Comment>>any());
        }
    }

    private Comment comment(Long id, Long postId, Long userId) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setPostId(postId);
        comment.setUserId(userId);
        comment.setContent("一段回声");
        comment.setStatus(1);
        return comment;
    }
}
