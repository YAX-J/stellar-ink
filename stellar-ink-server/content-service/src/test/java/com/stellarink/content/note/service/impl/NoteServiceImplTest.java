package com.stellarink.content.note.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.redis.RedisCache;
import com.stellarink.common.redis.RedisUtils;
import com.stellarink.content.cache.ContentCache;
import com.stellarink.content.note.mapper.NoteMapper;
import com.stellarink.content.note.pojo.Note;
import com.stellarink.content.post.mapper.PostViewMapper;
import com.stellarink.sharedmodel.dto.note.NoteUpdateDTO;
import com.stellarink.sharedmodel.dto.note.NoteQueryDTO;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.NoteType;
import com.stellarink.sharedmodel.enums.NoteVisibility;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.vo.note.NoteDetailVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.time.LocalDate;
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

class NoteServiceImplTest {

    private final NoteMapper noteMapper = mock(NoteMapper.class);
    private final PostViewMapper postViewMapper = mock(PostViewMapper.class);
    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final ContentCache cache = new ContentCache(new RedisCache(redisUtils));
    private final NoteServiceImpl noteService = new NoteServiceImpl(noteMapper, postViewMapper, cache);

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Note.class);
    }

    @Test
    void shouldHidePrivateNoteFromAnonymousVisitor() {
        when(noteMapper.selectById(1L)).thenReturn(note(1L, 10L, NoteVisibility.PRIVATE, 1));

        try (MockedStatic<StpUtil> token = Mockito.mockStatic(StpUtil.class)) {
            token.when(StpUtil::isLogin).thenReturn(false);

            assertNotFound(() -> noteService.detail(1L));
        }
    }

    @Test
    void shouldForcePublicAndPublishedFiltersOnPublicPage() {
        when(noteMapper.selectPage(
                Mockito.<Page<Note>>any(), Mockito.<LambdaQueryWrapper<Note>>any()))
                .thenAnswer(invocation -> {
                    LambdaQueryWrapper<Note> wrapper = invocation.getArgument(1);
                    assertThat(wrapper.getSqlSegment()).contains("status", "visibility");
                    assertThat(wrapper.getParamNameValuePairs().values())
                            .contains(1, NoteVisibility.PUBLIC.name());
                    return new Page<Note>(1, 10);
                });

        assertThat(noteService.page(new NoteQueryDTO()).getRecords()).isEmpty();
    }

    @Test
    void shouldReturnOnlyOwnedPublishedDueNotesWithReviewMetadata() {
        LocalDateTime verifiedAt = LocalDateTime.now().minusDays(200);
        Note expired = note(1L, 10L, NoteVisibility.PRIVATE, 1);
        expired.setVerifiedAt(verifiedAt);
        when(noteMapper.selectPage(
                Mockito.<Page<Note>>any(), Mockito.<LambdaQueryWrapper<Note>>any()))
                .thenAnswer(invocation -> {
                    LambdaQueryWrapper<Note> wrapper = invocation.getArgument(1);
                    assertThat(wrapper.getSqlSegment())
                            .contains("user_id", "status", "verified_at IS NULL", "verified_at <");
                    assertThat(wrapper.getParamNameValuePairs().values()).contains(10L, 1);
                    Page<Note> result = new Page<>(1, 10);
                    result.setRecords(List.of(expired));
                    result.setTotal(1);
                    return result;
                });

        try (MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::loginId).thenReturn(10L);
            NoteQueryDTO query = new NoteQueryDTO();
            query.setReviewState("DUE");

            var page = noteService.review(query);

            assertThat(page.getRecords()).singleElement().satisfies(note -> {
                assertThat(note.getReviewState()).isEqualTo("EXPIRED");
                assertThat(note.getReviewDueAt()).isEqualTo(verifiedAt.plusDays(180));
            });
        }
    }

    @Test
    void shouldHidePrivateNoteFromOtherAdmin() {
        when(noteMapper.selectById(1L)).thenReturn(note(1L, 10L, NoteVisibility.PRIVATE, 1));

        try (MockedStatic<StpUtil> token = Mockito.mockStatic(StpUtil.class);
             MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            token.when(StpUtil::isLogin).thenReturn(true);
            auth.when(AuthHelper::loginId).thenReturn(99L);
            auth.when(AuthHelper::currentRole).thenReturn(Role.ADMIN);

            assertNotFound(() -> noteService.detail(1L));
        }
    }

    @Test
    void shouldAllowOwnerToReadPrivateNoteWithoutCountingView() {
        Note note = note(1L, 10L, NoteVisibility.PRIVATE, 1);
        when(noteMapper.selectById(1L)).thenReturn(note);

        try (MockedStatic<StpUtil> token = Mockito.mockStatic(StpUtil.class);
             MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            token.when(StpUtil::isLogin).thenReturn(true);
            auth.when(AuthHelper::loginId).thenReturn(10L);

            assertThat(noteService.detail(1L).getId()).isEqualTo(1L);
            verify(noteMapper, never()).update(Mockito.<Note>isNull(), any());
            verify(postViewMapper, never()).findViewedAt(any());
            verify(redisUtils, never()).set(anyString(), any(), any());
        }
    }

    @Test
    void shouldCountAnonymousViewAndReturnLatestPublicNote() {
        Note before = note(1L, 10L, NoteVisibility.PUBLIC, 1);
        Note after = note(1L, 10L, NoteVisibility.PUBLIC, 1);
        after.setViewCount(1);
        when(noteMapper.selectById(1L)).thenReturn(before, before, after);

        try (MockedStatic<StpUtil> token = Mockito.mockStatic(StpUtil.class)) {
            token.when(StpUtil::isLogin).thenReturn(false);

            assertThat(noteService.detail(1L).getViewCount()).isEqualTo(1);
            verify(noteMapper).update(Mockito.<Note>isNull(), any());
            verify(redisUtils).set(anyString(), any(), any());
        }
    }

    @Test
    void shouldCountPublicNoteViewWhenDetailCacheHits() {
        var cached = new NoteDetailVO();
        cached.setId(1L);
        cached.setUserId(10L);
        cached.setViewCount(4);
        Note before = note(1L, 10L, NoteVisibility.PUBLIC, 1);
        before.setViewCount(4);
        Note after = note(1L, 10L, NoteVisibility.PUBLIC, 1);
        after.setViewCount(5);
        when(redisUtils.get(anyString(), eq(NoteDetailVO.class)))
                .thenReturn(cached);
        when(noteMapper.selectById(1L)).thenReturn(before, after);
        when(postViewMapper.findViewedAt(99L)).thenReturn(LocalDate.now().minusDays(1));

        try (MockedStatic<StpUtil> token = Mockito.mockStatic(StpUtil.class);
             MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            token.when(StpUtil::isLogin).thenReturn(true);
            auth.when(AuthHelper::loginId).thenReturn(99L);

            assertThat(noteService.detail(1L).getViewCount()).isEqualTo(5);
            verify(postViewMapper).touchToday(99L);
            verify(noteMapper).update(Mockito.<Note>isNull(), any());
            verify(redisUtils).set(anyString(), any(), any());
        }
    }

    @Test
    void shouldReturnCachedPublicNoteWithoutCountingOwnerView() {
        var cached = new NoteDetailVO();
        cached.setId(1L);
        cached.setUserId(10L);
        when(redisUtils.get(anyString(), eq(NoteDetailVO.class)))
                .thenReturn(cached);

        try (MockedStatic<StpUtil> token = Mockito.mockStatic(StpUtil.class);
             MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            token.when(StpUtil::isLogin).thenReturn(true);
            auth.when(AuthHelper::loginId).thenReturn(10L);

            assertThat(noteService.detail(1L)).isSameAs(cached);
            verify(noteMapper, never()).selectById(any());
            verify(postViewMapper, never()).findViewedAt(any());
        }
    }

    @Test
    void shouldForbidOtherUserUpdatingNoteEvenWhenAdmin() {
        when(noteMapper.selectById(1L)).thenReturn(note(1L, 10L, NoteVisibility.PRIVATE, 1));

        try (MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::loginId).thenReturn(99L);
            auth.when(AuthHelper::currentRole).thenReturn(Role.ADMIN);

            assertForbidden(() -> noteService.update(1L, new NoteUpdateDTO()));
            verify(noteMapper, never()).updateById(any(Note.class));
        }
    }

    @Test
    void shouldForbidOtherUserDeletingNoteEvenWhenAdmin() {
        when(noteMapper.selectById(1L)).thenReturn(note(1L, 10L, NoteVisibility.PRIVATE, 1));

        try (MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::loginId).thenReturn(99L);
            auth.when(AuthHelper::currentRole).thenReturn(Role.ADMIN);

            assertForbidden(() -> noteService.delete(1L));
            verify(noteMapper, never()).deleteById(1L);
        }
    }

    @Test
    void shouldNeverCountPrivateNoteView() {
        when(noteMapper.selectById(1L)).thenReturn(note(1L, 10L, NoteVisibility.PRIVATE, 1));

        assertThat(noteService.recordView(1L)).isFalse();
        verify(noteMapper, never()).update(Mockito.<Note>isNull(), any());
        verify(postViewMapper, never()).findViewedAt(any());
    }

    private void assertNotFound(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    private void assertForbidden(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    private Note note(Long id, Long userId, NoteVisibility visibility, int status) {
        LocalDateTime now = LocalDateTime.now();
        Note note = new Note();
        note.setId(id);
        note.setUserId(userId);
        note.setTitle("只属于作者的笔记");
        note.setContent("## 结论\n保持最小权限。");
        note.setTags("java,spring");
        note.setNoteType(NoteType.FIX.name());
        note.setVisibility(visibility.name());
        note.setStatus(status);
        note.setWordCount(12);
        note.setViewCount(0);
        note.setCreatedAt(now);
        note.setUpdatedAt(now);
        return note;
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
