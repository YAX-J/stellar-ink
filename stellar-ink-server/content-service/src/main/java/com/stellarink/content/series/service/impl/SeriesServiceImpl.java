package com.stellarink.content.series.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.common.util.Pagination;
import com.stellarink.content.note.mapper.NoteMapper;
import com.stellarink.content.note.pojo.Note;
import com.stellarink.content.post.mapper.PostMapper;
import com.stellarink.content.post.pojo.Post;
import com.stellarink.content.series.mapper.SeriesMapper;
import com.stellarink.content.series.pojo.Series;
import com.stellarink.content.series.service.SeriesService;
import com.stellarink.sharedmodel.dto.series.SeriesCreateDTO;
import com.stellarink.sharedmodel.dto.series.SeriesUpdateDTO;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.NoteVisibility;
import com.stellarink.sharedmodel.vo.series.SeriesBriefVO;
import com.stellarink.sharedmodel.vo.series.SeriesDetailVO;
import com.stellarink.sharedmodel.vo.series.SeriesVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SeriesServiceImpl implements SeriesService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int PUBLISHED = 1;
    private static final String KIND_POST = "post";
    private static final String KIND_NOTE = "note";

    private final SeriesMapper seriesMapper;
    private final PostMapper postMapper;
    private final NoteMapper noteMapper;

    @Override
    public IPage<SeriesVO> page(int page, int size) {
        Pagination.requireValid(page, size);
        IPage<Series> result = seriesMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Series>()
                        .orderByDesc(Series::getUpdatedAt)
                        .orderByDesc(Series::getId));
        return result.convert(this::toVO);
    }

    @Override
    public SeriesDetailVO detail(Long id) {
        Series series = requireSeries(id);
        List<SeriesDetailVO.ItemVO> items = orderedItems(id);
        SeriesDetailVO vo = new SeriesDetailVO();
        vo.setId(series.getId());
        vo.setUserId(series.getUserId());
        vo.setTitle(series.getTitle());
        vo.setDescription(series.getDescription());
        vo.setCreatedAt(series.getCreatedAt());
        vo.setUpdatedAt(series.getUpdatedAt());
        vo.setTotal(items.size());
        vo.setItems(items);
        return vo;
    }

    @Override
    public Long create(SeriesCreateDTO dto) {
        LocalDateTime now = LocalDateTime.now();
        Series series = new Series();
        series.setUserId(AuthHelper.loginId());
        series.setTitle(dto.getTitle().trim());
        series.setDescription(StringUtils.hasText(dto.getDescription()) ? dto.getDescription().trim() : "");
        series.setCreatedAt(now);
        series.setUpdatedAt(now);
        seriesMapper.insert(series);
        return series.getId();
    }

    @Override
    public void update(Long id, SeriesUpdateDTO dto) {
        Series series = requireSeries(id);
        ensureOwned(series);
        if (StringUtils.hasText(dto.getTitle())) {
            series.setTitle(dto.getTitle().trim());
        }
        if (dto.getDescription() != null) {
            series.setDescription(dto.getDescription().trim());
        }
        series.setUpdatedAt(LocalDateTime.now());
        seriesMapper.updateById(series);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Series series = requireSeries(id);
        ensureOwned(series);
        /* 先移出篇目再删系列：留下指向空系列的篇目，阅读页会显示「第 3 篇 / 共 0 篇」 */
        postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getSeriesId, id)
                .set(Post::getSeriesId, null)
                .set(Post::getSeriesOrder, null));
        noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                .eq(Note::getSeriesId, id)
                .set(Note::getSeriesId, null)
                .set(Note::getSeriesOrder, null));
        seriesMapper.deleteById(id);
    }

    @Override
    public void assertAttachable(Long seriesId) {
        if (seriesId == null || seriesId == 0L) {
            return;
        }
        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw BusinessExceptionHelper.of("这个系列不存在，可能已被删除。");
        }
        Long userId = AuthHelper.loginId();
        if (!userId.equals(series.getUserId())) {
            throw BusinessExceptionHelper.of(ErrorCode.FORBIDDEN, "只能把自己的内容放进自己的系列。");
        }
    }

    @Override
    public int nextOrder(Long seriesId) {
        Post lastPost = postMapper.selectOne(new LambdaQueryWrapper<Post>()
                .eq(Post::getSeriesId, seriesId)
                .isNotNull(Post::getSeriesOrder)
                .select(Post::getSeriesOrder)
                .orderByDesc(Post::getSeriesOrder)
                .last("LIMIT 1"));
        Note lastNote = noteMapper.selectOne(new LambdaQueryWrapper<Note>()
                .eq(Note::getSeriesId, seriesId)
                .isNotNull(Note::getSeriesOrder)
                .select(Note::getSeriesOrder)
                .orderByDesc(Note::getSeriesOrder)
                .last("LIMIT 1"));
        int max = 0;
        if (lastPost != null && lastPost.getSeriesOrder() != null) {
            max = Math.max(max, lastPost.getSeriesOrder());
        }
        if (lastNote != null && lastNote.getSeriesOrder() != null) {
            max = Math.max(max, lastNote.getSeriesOrder());
        }
        return max + 1;
    }

    @Override
    public SeriesBriefVO brief(Long seriesId, Long contentId, String kind) {
        if (seriesId == null) {
            return null;
        }
        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            return null;
        }
        List<SeriesDetailVO.ItemVO> items = orderedItems(seriesId);
        SeriesBriefVO vo = new SeriesBriefVO();
        vo.setId(series.getId());
        vo.setTitle(series.getTitle());
        vo.setTotal(items.size());
        vo.setIndex(null);
        for (int i = 0; i < items.size(); i++) {
            SeriesDetailVO.ItemVO item = items.get(i);
            if (item.getKind().equals(kind) && item.getId().equals(contentId)) {
                vo.setIndex(i + 1);
                break;
            }
        }
        return vo;
    }

    /**
     * 系列内公开篇目：文章与公开笔记合并后按 (序号, id) 排。
     *
     * <p>序号为 NULL 的（迁移前的老数据）排在最后，而不是被当成 0 插到最前面 ——
     * 「没填过序号」与「排在第一」是两件事。
     */
    private List<SeriesDetailVO.ItemVO> orderedItems(Long seriesId) {
        List<SeriesDetailVO.ItemVO> items = new ArrayList<>();
        postMapper.selectList(new LambdaQueryWrapper<Post>()
                        .eq(Post::getSeriesId, seriesId)
                        .eq(Post::getStatus, PUBLISHED)
                        .select(Post::getId, Post::getTitle, Post::getWordCount,
                                Post::getSeriesOrder, Post::getCreatedAt))
                .forEach(post -> items.add(item(KIND_POST, post.getId(), post.getTitle(),
                        post.getWordCount(), post.getSeriesOrder(), post.getCreatedAt())));
        noteMapper.selectList(new LambdaQueryWrapper<Note>()
                        .eq(Note::getSeriesId, seriesId)
                        .eq(Note::getStatus, PUBLISHED)
                        .eq(Note::getVisibility, NoteVisibility.PUBLIC.name())
                        .select(Note::getId, Note::getTitle, Note::getWordCount,
                                Note::getSeriesOrder, Note::getCreatedAt))
                .forEach(note -> items.add(item(KIND_NOTE, note.getId(), note.getTitle(),
                        note.getWordCount(), note.getSeriesOrder(), note.getCreatedAt())));
        items.sort(Comparator
                .comparingInt((SeriesDetailVO.ItemVO item) ->
                        item.getOrder() == null ? Integer.MAX_VALUE : item.getOrder())
                .thenComparing(SeriesDetailVO.ItemVO::getId));
        return items;
    }

    private SeriesDetailVO.ItemVO item(String kind, Long id, String title, Integer wordCount,
                                       Integer order, LocalDateTime createdAt) {
        SeriesDetailVO.ItemVO item = new SeriesDetailVO.ItemVO();
        item.setKind(kind);
        item.setId(id);
        item.setTitle(title);
        item.setWordCount(wordCount);
        item.setOrder(order);
        item.setDate(createdAt == null ? null : createdAt.toLocalDate().format(DATE));
        return item;
    }

    private SeriesVO toVO(Series series) {
        SeriesVO vo = new SeriesVO();
        vo.setId(series.getId());
        vo.setUserId(series.getUserId());
        vo.setTitle(series.getTitle());
        vo.setDescription(series.getDescription());
        vo.setUpdatedAt(series.getUpdatedAt());
        int postCount = Math.toIntExact(postMapper.selectCount(new LambdaQueryWrapper<Post>()
                .eq(Post::getSeriesId, series.getId())
                .eq(Post::getStatus, PUBLISHED)));
        int noteCount = Math.toIntExact(noteMapper.selectCount(new LambdaQueryWrapper<Note>()
                .eq(Note::getSeriesId, series.getId())
                .eq(Note::getStatus, PUBLISHED)
                .eq(Note::getVisibility, NoteVisibility.PUBLIC.name())));
        vo.setPostCount(postCount);
        vo.setNoteCount(noteCount);
        vo.setItemCount(postCount + noteCount);
        return vo;
    }

    private Series requireSeries(Long id) {
        Series series = seriesMapper.selectById(id);
        if (series == null) {
            throw BusinessExceptionHelper.of(ErrorCode.NOT_FOUND, "这个系列不存在");
        }
        return series;
    }

    private void ensureOwned(Series series) {
        if (!AuthHelper.loginId().equals(series.getUserId())) {
            throw BusinessExceptionHelper.of(ErrorCode.FORBIDDEN, "只有系列创建者能修改它");
        }
    }
}
