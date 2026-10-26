package com.stellarink.content.series.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.content.note.mapper.NoteMapper;
import com.stellarink.content.note.pojo.Note;
import com.stellarink.content.post.mapper.PostMapper;
import com.stellarink.content.post.pojo.Post;
import com.stellarink.content.series.mapper.SeriesMapper;
import com.stellarink.content.series.pojo.Series;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.NoteVisibility;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.vo.series.SeriesDetailVO;
import com.stellarink.sharedmodel.vo.series.SeriesVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 系列的三条口径：归属、顺序、计数。
 *
 * <p>这三条一旦松动，症状分别是「别人的系列能被我挂内容」「阅读顺序随 id 乱跳」
 * 「列表说 5 篇、点进去只有 3 篇」—— 都是看不出来但会持续错下去的那类。
 */
class SeriesServiceImplTest {

    private final SeriesMapper seriesMapper = mock(SeriesMapper.class);
    private final PostMapper postMapper = mock(PostMapper.class);
    private final NoteMapper noteMapper = mock(NoteMapper.class);
    private final SeriesServiceImpl seriesService = new SeriesServiceImpl(seriesMapper, postMapper, noteMapper);

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, Series.class);
        TableInfoHelper.initTableInfo(assistant, Post.class);
        TableInfoHelper.initTableInfo(assistant, Note.class);
    }

    @Test
    void shouldRefuseAttachingContentToSomeoneElsesSeries() {
        when(seriesMapper.selectById(7L)).thenReturn(series(7L, 10L));

        try (MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::loginId).thenReturn(99L);

            assertThatThrownBy(() -> seriesService.assertAttachable(7L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.FORBIDDEN.getCode());
        }
    }

    @Test
    void shouldTreatNullAndZeroAsNoLookup() {
        /* null = 不修改、0 = 移出：两者都不该去查库，更不该因为「查不到系列」报 400 */
        seriesService.assertAttachable(null);
        seriesService.assertAttachable(0L);

        verifyNoInteractions(seriesMapper);
    }

    @Test
    void shouldOrderItemsBySeriesOrderAndPutNullOrderLast() {
        when(seriesMapper.selectById(7L)).thenReturn(series(7L, 10L));
        when(postMapper.selectList(Mockito.<LambdaQueryWrapper<Post>>any()))
                .thenReturn(List.of(post(2L, "第二篇", 2), post(3L, "没排过序号", null)));
        when(noteMapper.selectList(Mockito.<LambdaQueryWrapper<Note>>any()))
                .thenReturn(List.of(note(1L, "第一篇", 1)));

        SeriesDetailVO detail = seriesService.detail(7L);

        assertThat(detail.getTotal()).isEqualTo(3);
        assertThat(detail.getItems()).extracting(SeriesDetailVO.ItemVO::getTitle)
                .as("按 series_order 升序；NULL 序号排最后，而不是被当成 0 插到最前")
                .containsExactly("第一篇", "第二篇", "没排过序号");
        assertThat(detail.getItems()).extracting(SeriesDetailVO.ItemVO::getKind)
                .containsExactly("note", "post", "post");
    }

    @Test
    void shouldCountOnlyPublicItemsInList() {
        Page<Series> result = new Page<>(1, 20);
        result.setRecords(List.of(series(7L, 10L)));
        when(seriesMapper.selectPage(
                Mockito.<Page<Series>>any(), Mockito.<LambdaQueryWrapper<Series>>any()))
                .thenReturn(result);
        when(postMapper.selectCount(Mockito.<LambdaQueryWrapper<Post>>any())).thenReturn(2L);
        when(noteMapper.selectCount(Mockito.<LambdaQueryWrapper<Note>>any())).thenReturn(3L);

        SeriesVO vo = seriesService.page(1, 20).getRecords().get(0);

        assertThat(vo.getPostCount()).isEqualTo(2);
        assertThat(vo.getNoteCount()).isEqualTo(3);
        assertThat(vo.getItemCount())
                .as("itemCount 必须等于两个来源之和，否则列表数与点进去能读到的篇数会对不上")
                .isEqualTo(5);
    }

    private Series series(Long id, Long userId) {
        Series series = new Series();
        series.setId(id);
        series.setUserId(userId);
        series.setTitle("Redis 超时排查");
        series.setDescription("从现象到结论的一条线");
        series.setCreatedAt(LocalDateTime.now());
        series.setUpdatedAt(LocalDateTime.now());
        return series;
    }

    private Post post(Long id, String title, Integer order) {
        Post post = new Post();
        post.setId(id);
        post.setTitle(title);
        post.setStatus(1);
        post.setWordCount(100);
        post.setSeriesId(7L);
        post.setSeriesOrder(order);
        post.setCreatedAt(LocalDateTime.now());
        return post;
    }

    private Note note(Long id, String title, Integer order) {
        Note note = new Note();
        note.setId(id);
        note.setTitle(title);
        note.setStatus(1);
        note.setVisibility(NoteVisibility.PUBLIC.name());
        note.setWordCount(80);
        note.setSeriesId(7L);
        note.setSeriesOrder(order);
        note.setCreatedAt(LocalDateTime.now());
        return note;
    }
}
