package com.stellarink.content.corpus.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.stellarink.content.corpus.service.InternalCorpusService;
import com.stellarink.content.note.mapper.NoteMapper;
import com.stellarink.content.note.pojo.Note;
import com.stellarink.content.post.mapper.PostMapper;
import com.stellarink.content.post.pojo.Post;
import com.stellarink.sharedmodel.enums.CorpusKind;
import com.stellarink.sharedmodel.enums.NoteVisibility;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.vo.corpus.CorpusContentVO;
import com.stellarink.sharedmodel.vo.corpus.CorpusSliceVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 语料读取的两条底线 + 三个诚实性口径。
 *
 * <p>底线：**私有笔记与草稿不得出现在清单里**，也**不得按 id 取到正文**。
 * 这两个用例是「知识库不会泄漏私有内容」的回归网 —— 它们红了，说明有人的可见性条件被改松了。
 *
 * <p>诚实性：截断必须如实上报（否则对账会以为「这些文章被删了」去删索引）、
 * 变更必须能被哈希识别、`since` 必须是**严格大于**（否则游标会卡在同一个时间点上反复拉同一批）。
 */
class InternalCorpusServiceImplTest {

    private PostMapper postMapper;
    private NoteMapper noteMapper;
    private InternalCorpusServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Post.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Note.class);
    }

    @BeforeEach
    void setUp() {
        postMapper = Mockito.mock(PostMapper.class);
        noteMapper = Mockito.mock(NoteMapper.class);
        service = new InternalCorpusServiceImpl(postMapper, noteMapper);
    }

    @Test
    @DisplayName("清单：只查已发布文章 + 已发布且公开的笔记（可见性条件必须在 SQL 里）")
    void sliceFiltersInSqlNotInMemory() {
        when(postMapper.selectList(any())).thenReturn(List.of(post(1L, "文章", "正文", 1)));
        when(noteMapper.selectList(any())).thenReturn(List.of(note(2L, "公开笔记", "正文",
                NoteVisibility.PUBLIC, 1)));

        CorpusSliceVO slice = service.slice(null, null, 10);

        assertThat(slice.getItems()).hasSize(2);
        assertThat(slice.getItems().get(0).getKind()).isEqualTo(CorpusKind.POST);
        assertThat(slice.getItems().get(1).getKind()).isEqualTo(CorpusKind.NOTE);

        String postSql = capturedWrapper(postMapper).getSqlSegment();
        assertThat(postSql).contains("status");

        String noteSql = capturedWrapper(noteMapper).getSqlSegment();
        // 少任何一个条件都意味着私有内容会进知识库 —— 两个都要在
        assertThat(noteSql).contains("status").contains("visibility");
    }

    @Test
    @DisplayName("单篇正文：草稿文章取不到（NOT_FOUND，而不是返回草稿内容）")
    void contentRejectsUnpublishedPost() {
        when(postMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.content(CorpusKind.POST, 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在或未公开");

        // 关键：过滤条件里必须带 status，否则草稿会被当成正常内容返回
        assertThat(capturedOneWrapper(postMapper).getSqlSegment()).contains("status");
    }

    @Test
    @DisplayName("单篇正文：私有笔记取不到，且查询条件里同时有 status 与 visibility")
    void contentRejectsPrivateNote() {
        when(noteMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.content(CorpusKind.NOTE, 9L))
                .isInstanceOf(BusinessException.class);

        String sql = capturedOneWrapper(noteMapper).getSqlSegment();
        assertThat(sql).contains("status").contains("visibility");
    }

    @Test
    @DisplayName("单篇正文：公开笔记能取到，docHash 与清单口径一致")
    void contentReturnsPublicNote() {
        Note note = note(5L, "标题", "正文", NoteVisibility.PUBLIC, 1);
        when(noteMapper.selectOne(any())).thenReturn(note);

        CorpusContentVO content = service.content(CorpusKind.NOTE, 5L);

        assertThat(content.getKind()).isEqualTo(CorpusKind.NOTE);
        assertThat(content.getId()).isEqualTo(5L);
        assertThat(content.getContent()).isEqualTo("正文");
        assertThat(content.getDocHash()).isEqualTo(
                com.stellarink.common.util.Hashes.docHash("标题", "正文"));
    }

    @Test
    @DisplayName("截断必须如实上报：拿到 limit+1 条时 truncated=true，且只回 limit 条")
    void reportsTruncation() {
        List<Post> posts = new ArrayList<>();
        for (long id = 1; id <= 3; id++) {
            posts.add(post(id, "标题" + id, "正文" + id, 1));
        }
        when(postMapper.selectList(any())).thenReturn(posts);
        when(noteMapper.selectList(any())).thenReturn(List.of());

        CorpusSliceVO slice = service.slice(null, null, 2);

        assertThat(slice.getTruncated()).isTrue();
        assertThat(slice.getItems()).hasSize(2);
        assertThat(slice.getMaxUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("不截断时 truncated=false，且 limit 被夹到合法区间")
    void clampsLimit() {
        when(postMapper.selectList(any())).thenReturn(List.of(post(1L, "a", "b", 1)));
        when(noteMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.slice(null, null, 0).getLimit())
                .isEqualTo(InternalCorpusService.DEFAULT_LIMIT);
        assertThat(service.slice(null, null, 99999).getLimit())
                .isEqualTo(InternalCorpusService.MAX_LIMIT);
        assertThat(service.slice(null, null, 5).getTruncated()).isFalse();
    }

    @Test
    @DisplayName("since 是严格大于（用 gt 而不是 ge），否则游标会在同一时间点上反复拉同一批")
    void sinceIsExclusive() {
        when(postMapper.selectList(any())).thenReturn(List.of());
        when(noteMapper.selectList(any())).thenReturn(List.of());

        service.slice(LocalDateTime.of(2026, 10, 4, 18, 0), null, 10);

        String postSql = capturedWrapper(postMapper).getSqlSegment();
        assertThat(postSql).contains("updated_at").contains(">");
        assertThat(postSql).doesNotContain(">=");
    }

    @Test
    @DisplayName("内容变更会改变 docHash（对账靠它识别「哪几篇需要重嵌」）")
    void docHashTracksChanges() {
        when(noteMapper.selectList(any())).thenReturn(List.of());
        when(postMapper.selectList(any())).thenReturn(List.of(post(1L, "标题", "改前", 1)));
        String before = service.slice(null, null, 10).getItems().get(0).getDocHash();

        when(postMapper.selectList(any())).thenReturn(List.of(post(1L, "标题", "改后", 1)));
        String after = service.slice(null, null, 10).getItems().get(0).getDocHash();

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @DisplayName("没有内容时返回空清单而不是 null（调用方要对它做集合运算）")
    void emptyCorpusIsAnEmptyList() {
        when(postMapper.selectList(any())).thenReturn(List.of());
        when(noteMapper.selectList(any())).thenReturn(List.of());

        CorpusSliceVO slice = service.slice(null, null, 10);

        assertThat(slice.getItems()).isEmpty();
        assertThat(slice.getTruncated()).isFalse();
        assertThat(slice.getMaxUpdatedAt()).isNull();
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<Post> capturedWrapper(PostMapper mapper) {
        ArgumentCaptor<LambdaQueryWrapper<Post>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(mapper, Mockito.atLeastOnce()).selectList(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<Note> capturedWrapper(NoteMapper mapper) {
        ArgumentCaptor<LambdaQueryWrapper<Note>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(mapper, Mockito.atLeastOnce()).selectList(captor.capture());
        return captor.getValue();
    }

    /** 单篇正文走的是 selectOne（不是 selectList），要单独捕获。 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<Post> capturedOneWrapper(PostMapper mapper) {
        ArgumentCaptor<LambdaQueryWrapper<Post>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(mapper, Mockito.atLeastOnce()).selectOne(captor.capture());
        return captor.getValue();
    }

    /** 单篇正文走的是 selectOne（不是 selectList），要单独捕获。 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<Note> capturedOneWrapper(NoteMapper mapper) {
        ArgumentCaptor<LambdaQueryWrapper<Note>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(mapper, Mockito.atLeastOnce()).selectOne(captor.capture());
        return captor.getValue();
    }

    private Post post(Long id, String title, String content, int status) {
        Post post = new Post();
        post.setId(id);
        post.setTitle(title);
        post.setContent(content);
        post.setStatus(status);
        post.setUpdatedAt(LocalDateTime.of(2026, 10, 4, 10, 0).plusMinutes(id));
        return post;
    }

    private Note note(Long id, String title, String content, NoteVisibility visibility, int status) {
        Note note = new Note();
        note.setId(id);
        note.setTitle(title);
        note.setContent(content);
        note.setVisibility(visibility.name());
        note.setStatus(status);
        note.setUpdatedAt(LocalDateTime.of(2026, 10, 4, 10, 0).plusMinutes(id));
        return note;
    }
}
