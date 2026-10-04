package com.stellarink.content.corpus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.common.util.Hashes;
import com.stellarink.content.corpus.service.InternalCorpusService;
import com.stellarink.content.note.mapper.NoteMapper;
import com.stellarink.content.note.pojo.Note;
import com.stellarink.content.post.mapper.PostMapper;
import com.stellarink.content.post.pojo.Post;
import com.stellarink.sharedmodel.enums.CorpusKind;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.NoteVisibility;
import com.stellarink.sharedmodel.vo.corpus.CorpusContentVO;
import com.stellarink.sharedmodel.vo.corpus.CorpusItemVO;
import com.stellarink.sharedmodel.vo.corpus.CorpusSliceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 语料读取实现。
 *
 * <p>三条刻意的取舍：
 *
 * <ol>
 *   <li><b>可见性条件写在这里，且只写在这里</b>：文章的 {@code status=1}、笔记的
 *       {@code status=1 + visibility=PUBLIC}。这是知识库的边界，重复实现一次就是泄漏风险。</li>
 *   <li><b>用 limit+1 行判定截断</b>（而不是先 count 再查）：一次查询就够，
 *       并且截断与否是「实际拿到的比要的多」这个事实，不会被并发写改掉。</li>
 *   <li><b>两种 kind 分别查询后归并</b>：它们是两张表，没有跨表分页的干净做法；
 *       语料规模是「一个博客的公开内容」，归并在内存里做最直白。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class InternalCorpusServiceImpl implements InternalCorpusService {

    /** 已发布（post / note 的 status 口径一致：0 草稿 / 1 已发布）。 */
    private static final int STATUS_PUBLISHED = 1;

    private final PostMapper postMapper;
    private final NoteMapper noteMapper;

    @Override
    public CorpusSliceVO slice(LocalDateTime since, List<Long> ids, int limit) {
        int effectiveLimit = clampLimit(limit);
        List<Long> effectiveIds = (ids == null || ids.isEmpty()) ? null : ids;

        // 各取 limit+1：任何一个 kind 单独超过上限，都能被下面的 size > limit 判出来
        List<Post> posts = postMapper.selectList(
                postWrapper(since, effectiveIds).last("LIMIT " + (effectiveLimit + 1)));
        List<Note> notes = noteMapper.selectList(
                noteWrapper(since, effectiveIds).last("LIMIT " + (effectiveLimit + 1)));

        List<CorpusItemVO> merged = new ArrayList<>(posts.size() + notes.size());
        posts.forEach(post -> merged.add(toItem(CorpusKind.POST, post.getId(), post.getTitle(),
                post.getContent(), post.getUpdatedAt())));
        notes.forEach(note -> merged.add(toItem(CorpusKind.NOTE, note.getId(), note.getTitle(),
                note.getContent(), note.getUpdatedAt())));
        merged.sort(Comparator
                .comparing(CorpusItemVO::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(CorpusItemVO::getId));

        boolean truncated = merged.size() > effectiveLimit;
        List<CorpusItemVO> items = truncated
                ? new ArrayList<>(merged.subList(0, effectiveLimit))
                : merged;

        CorpusSliceVO slice = new CorpusSliceVO();
        slice.setItems(items);
        slice.setTruncated(truncated);
        slice.setLimit(effectiveLimit);
        slice.setMaxUpdatedAt(items.isEmpty() ? null : items.get(items.size() - 1).getUpdatedAt());
        return slice;
    }

    @Override
    public CorpusContentVO content(CorpusKind kind, Long id) {
        if (kind == null || id == null) {
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_MISSING, "缺少 kind 或 id");
        }
        if (kind == CorpusKind.POST) {
            Post post = postMapper.selectOne(new LambdaQueryWrapper<Post>()
                    .eq(Post::getId, id)
                    .eq(Post::getStatus, STATUS_PUBLISHED));
            if (post == null) {
                throw BusinessExceptionHelper.of(ErrorCode.NOT_FOUND, "这篇文章不存在或未公开");
            }
            return toContent(CorpusKind.POST, post.getId(), post.getTitle(), post.getContent(),
                    post.getTags(), post.getUpdatedAt());
        }
        Note note = noteMapper.selectOne(new LambdaQueryWrapper<Note>()
                .eq(Note::getId, id)
                .eq(Note::getStatus, STATUS_PUBLISHED)
                .eq(Note::getVisibility, NoteVisibility.PUBLIC.name()));
        if (note == null) {
            throw BusinessExceptionHelper.of(ErrorCode.NOT_FOUND, "这条笔记不存在或未公开");
        }
        return toContent(CorpusKind.NOTE, note.getId(), note.getTitle(), note.getContent(),
                note.getTags(), note.getUpdatedAt());
    }

    /** 文章：只要已发布。 */
    private LambdaQueryWrapper<Post> postWrapper(LocalDateTime since, List<Long> ids) {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, STATUS_PUBLISHED);
        if (since != null) {
            wrapper.gt(Post::getUpdatedAt, since);
        }
        if (ids != null) {
            wrapper.in(Post::getId, ids);
        }
        return wrapper.orderByAsc(Post::getUpdatedAt).orderByAsc(Post::getId);
    }

    /** 笔记：已发布 **且** 公开。少任何一个条件都意味着私有内容会进知识库。 */
    private LambdaQueryWrapper<Note> noteWrapper(LocalDateTime since, List<Long> ids) {
        LambdaQueryWrapper<Note> wrapper = new LambdaQueryWrapper<Note>()
                .eq(Note::getStatus, STATUS_PUBLISHED)
                .eq(Note::getVisibility, NoteVisibility.PUBLIC.name());
        if (since != null) {
            wrapper.gt(Note::getUpdatedAt, since);
        }
        if (ids != null) {
            wrapper.in(Note::getId, ids);
        }
        return wrapper.orderByAsc(Note::getUpdatedAt).orderByAsc(Note::getId);
    }

    private CorpusItemVO toItem(CorpusKind kind, Long id, String title, String content,
                                LocalDateTime updatedAt) {
        CorpusItemVO item = new CorpusItemVO();
        item.setKind(kind);
        item.setId(id);
        item.setTitle(title);
        item.setDocHash(Hashes.docHash(title, content));
        item.setUpdatedAt(updatedAt);
        return item;
    }

    private CorpusContentVO toContent(CorpusKind kind, Long id, String title, String content,
                                      String tags, LocalDateTime updatedAt) {
        CorpusContentVO vo = new CorpusContentVO();
        vo.setKind(kind);
        vo.setId(id);
        vo.setTitle(title);
        vo.setContent(content);
        vo.setTags(tags);
        vo.setDocHash(Hashes.docHash(title, content));
        vo.setUpdatedAt(updatedAt);
        return vo;
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
