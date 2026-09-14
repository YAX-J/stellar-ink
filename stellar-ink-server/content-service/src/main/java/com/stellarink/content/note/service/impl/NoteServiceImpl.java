package com.stellarink.content.note.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.content.note.mapper.NoteMapper;
import com.stellarink.content.note.pojo.Note;
import com.stellarink.content.note.service.NoteService;
import com.stellarink.content.post.mapper.PostViewMapper;
import com.stellarink.sharedmodel.dto.note.NoteCreateDTO;
import com.stellarink.sharedmodel.dto.note.NoteQueryDTO;
import com.stellarink.sharedmodel.dto.note.NoteUpdateDTO;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.NoteType;
import com.stellarink.sharedmodel.enums.NoteVisibility;
import com.stellarink.sharedmodel.vo.note.NoteDetailVO;
import com.stellarink.sharedmodel.vo.note.NoteVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NoteServiceImpl implements NoteService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int SUMMARY_LEN = 60;
    private static final int PUBLISHED = 1;
    private static final int DRAFT = 0;

    private final NoteMapper noteMapper;
    /** 浏览量闸门与文章共用：该表只记「某用户某天已计过一次」，与内容类型无关 */
    private final PostViewMapper postViewMapper;

    @Override
    public IPage<NoteVO> page(NoteQueryDTO query) {
        LambdaQueryWrapper<Note> wrapper = new LambdaQueryWrapper<Note>()
                .eq(Note::getStatus, PUBLISHED)
                .eq(Note::getVisibility, NoteVisibility.PUBLIC.name());
        applyFilters(wrapper, query);
        applyOrder(wrapper, query.getOrderBy());
        IPage<Note> page = noteMapper.selectPage(new Page<>(query.getPage(), query.getSize()), wrapper);
        return page.convert(this::toVO);
    }

    @Override
    public IPage<NoteVO> mine(NoteQueryDTO query) {
        LambdaQueryWrapper<Note> wrapper = new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, AuthHelper.loginId())
                .eq(query.getStatus() != null, Note::getStatus, query.getStatus());
        NoteVisibility visibility = NoteVisibility.parse(query.getVisibility());
        if (visibility != null) {
            wrapper.eq(Note::getVisibility, visibility.name());
        }
        applyFilters(wrapper, query);
        /* 我的笔记按更新时间倒序：草稿频繁改动，最近碰过的排最前 */
        wrapper.orderByDesc(Note::getUpdatedAt);
        applyOrder(wrapper, query.getOrderBy());
        IPage<Note> page = noteMapper.selectPage(new Page<>(query.getPage(), query.getSize()), wrapper);
        return page.convert(this::toVO);
    }

    private void applyFilters(LambdaQueryWrapper<Note> wrapper, NoteQueryDTO query) {
        wrapper.like(StringUtils.hasText(query.getTag()), Note::getTags, query.getTag());
        NoteType type = NoteType.parse(query.getNoteType());
        if (type != null) {
            wrapper.eq(Note::getNoteType, type.name());
        }
        wrapper.and(StringUtils.hasText(query.getKeyword()), w -> w
                .like(Note::getTitle, query.getKeyword())
                .or()
                .like(Note::getContent, query.getKeyword()));
    }

    /**
     * 排序：latest 最新（默认）/ hottest 最多浏览 / longest 篇幅最长。
     * 排序条件必须在同一处加：MyBatis-Plus 对同一列只保留先加入的条件。
     */
    private void applyOrder(LambdaQueryWrapper<Note> wrapper, String orderBy) {
        if ("hottest".equalsIgnoreCase(orderBy)) {
            wrapper.orderByDesc(Note::getViewCount);
        } else if ("longest".equalsIgnoreCase(orderBy)) {
            wrapper.orderByDesc(Note::getWordCount);
        }
        wrapper.orderByDesc(Note::getId);
    }

    @Override
    public NoteDetailVO detail(Long id) {
        Note note = requireNote(id);
        /* 私有笔记只有作者本人可读；其它人（含 ADMIN）一律 404，不暴露存在性 */
        ensureReadable(note);

        Long viewerId = currentLoginId();
        if (isPublicPublished(note) && (viewerId == null || !viewerId.equals(note.getUserId()))) {
            recordView(note.getId());
            note = requireNote(id);
        }

        NoteDetailVO vo = new NoteDetailVO();
        vo.setId(note.getId());
        vo.setUserId(note.getUserId());
        vo.setStatus(note.getStatus());
        vo.setTitle(note.getTitle());
        vo.setContent(note.getContent());
        vo.setTags(splitTags(note.getTags()));
        vo.setNoteType(note.getNoteType());
        NoteType type = NoteType.parseOrDefault(note.getNoteType());
        vo.setNoteTypeLabel(type.getLabel());
        vo.setNoteTypeGlyph(type.getGlyph());
        vo.setVisibility(note.getVisibility());
        vo.setWordCount(note.getWordCount());
        vo.setReadMinutes((int) Math.ceil(note.getWordCount() / 400.0));
        vo.setViewCount(note.getViewCount() == null ? 0 : note.getViewCount());
        vo.setDate(note.getCreatedAt().toLocalDate().format(DATE_FMT));
        vo.setVerifiedAt(note.getVerifiedAt());
        vo.setPrev(neighbor(note.getId(), true));
        vo.setNext(neighbor(note.getId(), false));
        return vo;
    }

    @Override
    public Long create(NoteCreateDTO dto) {
        NoteType type = NoteType.parseOrDefault(dto.getNoteType());
        NoteVisibility visibility = NoteVisibility.parseOrDefault(dto.getVisibility());
        int status = dto.getStatus() != null ? dto.getStatus() : DRAFT;
        String content = status == DRAFT ? optionalContent(dto.getContent()) : requireContent(dto.getContent());

        LocalDateTime now = LocalDateTime.now();
        Note entity = new Note();
        entity.setUserId(AuthHelper.loginId());
        entity.setTitle(StringUtils.hasText(dto.getTitle()) ? dto.getTitle().trim() : "未命名笔记");
        entity.setContent(content);
        entity.setTags(joinTags(dto.getTags()));
        entity.setNoteType(type.name());
        entity.setVisibility(visibility.name());
        entity.setStatus(status);
        entity.setWordCount(countWords(content));
        entity.setViewCount(0);
        entity.setVerifiedAt(null);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        noteMapper.insert(entity);
        log.info("新建笔记 id={} type={} visibility={} status={} title={}",
                entity.getId(), entity.getNoteType(), entity.getVisibility(), entity.getStatus(), entity.getTitle());
        return entity.getId();
    }

    @Override
    public void update(Long id, NoteUpdateDTO dto) {
        Note note = requireNote(id);
        ensureOwned(note);

        Integer nextStatus = dto.getStatus() == null ? note.getStatus() : dto.getStatus();
        if (StringUtils.hasText(dto.getTitle())) {
            note.setTitle(dto.getTitle().trim());
        }
        if (dto.getContent() != null) {
            String content = nextStatus != null && nextStatus == DRAFT
                    ? optionalContent(dto.getContent()) : requireContent(dto.getContent());
            note.setContent(content);
            note.setWordCount(countWords(content));
        }
        if (dto.getTags() != null) {
            note.setTags(joinTags(dto.getTags()));
        }
        if (StringUtils.hasText(dto.getNoteType())) {
            note.setNoteType(NoteType.parseOrDefault(dto.getNoteType()).name());
        }
        /* 显式传值才切换可见性，传 null 保持原状 */
        if (StringUtils.hasText(dto.getVisibility())) {
            note.setVisibility(NoteVisibility.parseOrDefault(dto.getVisibility()).name());
        }
        if (dto.getStatus() != null) {
            note.setStatus(dto.getStatus());
        }
        // 发布前必须真正有内容：草稿可以空，发布的笔记本一定不能空
        if (Integer.valueOf(PUBLISHED).equals(note.getStatus())) {
            requireContent(note.getContent());
        }
        note.setUpdatedAt(LocalDateTime.now());
        noteMapper.updateById(note);
        log.info("更新笔记 id={} visibility={} status={} title={}",
                id, note.getVisibility(), note.getStatus(), note.getTitle());
    }

    @Override
    public void delete(Long id) {
        Note note = requireNote(id);
        ensureOwned(note);
        noteMapper.deleteById(id);
        log.info("删除笔记 id={} title={}", id, note.getTitle());
    }

    @Override
    public LocalDateTime verify(Long id) {
        Note note = requireNote(id);
        ensureOwned(note);
        LocalDateTime now = LocalDateTime.now();
        noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                .eq(Note::getId, id)
                .set(Note::getVerifiedAt, now));
        log.info("笔记 {} 标记为已验证，结论截至 {}", id, now);
        return now;
    }

    @Override
    public boolean recordView(Long id) {
        Note note = noteMapper.selectById(id);
        /* 私有笔记与草稿不统计浏览量 */
        if (note == null || !isPublicPublished(note)) {
            return false;
        }
        Long viewerId = currentLoginId();
        if (viewerId != null && viewerId.equals(note.getUserId())) {
            return false;
        }
        boolean counted;
        if (viewerId != null) {
            /* 登录用户按天去重（与文章共用闸门表；先读后写，先写会让判断恒为「今天已计」） */
            LocalDate lastCounted = postViewMapper.findViewedAt(viewerId);
            counted = lastCounted == null || !lastCounted.equals(LocalDate.now());
            if (lastCounted == null) {
                postViewMapper.insertToday(viewerId);
            } else if (counted) {
                postViewMapper.touchToday(viewerId);
            }
        } else {
            counted = true;
        }
        if (counted) {
            noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                    .eq(Note::getId, id)
                    .setSql("view_count = view_count + 1"));
        }
        return counted;
    }

    private Note requireNote(Long id) {
        Note note = noteMapper.selectById(id);
        if (note == null) {
            throw BusinessExceptionHelper.of(ErrorCode.NOT_FOUND, "这条笔记不存在");
        }
        return note;
    }

    /**
     * 可读性判定：公开且已发布的笔记任何人可读；其余（草稿 / 私有）只有作者本人可读，
     * 其他人一律按 404 处理，避免通过错误码枚举出「这里有一条别人的私有笔记」。
     */
    private void ensureReadable(Note note) {
        if (isPublicPublished(note)) {
            return;
        }
        Long viewerId = currentLoginId();
        if (viewerId != null && viewerId.equals(note.getUserId())) {
            return;
        }
        throw BusinessExceptionHelper.of(ErrorCode.NOT_FOUND, "这条笔记不存在");
    }

    /**
     * 归属判定：笔记比文章更严格 —— 只有作者本人能改删，ADMIN 也不行。
     * 理由是「私有」必须是对作者的保证，站长能看别人私有笔记会让这个承诺失效。
     */
    private void ensureOwned(Note note) {
        if (AuthHelper.loginId().equals(note.getUserId())) {
            return;
        }
        throw BusinessExceptionHelper.of(ErrorCode.FORBIDDEN, "只能维护自己的笔记");
    }

    private boolean isPublicPublished(Note note) {
        return Integer.valueOf(PUBLISHED).equals(note.getStatus())
                && NoteVisibility.PUBLIC.name().equals(note.getVisibility());
    }

    /** 当前登录用户 id；未登录返回 null（不抛异常，供可选登录场景使用） */
    private Long currentLoginId() {
        return StpUtil.isLogin() ? AuthHelper.loginId() : null;
    }

    /** 相邻笔记：只在公开已发布范围内取，prev 取更新的一条，next 取更早的一条 */
    private NoteDetailVO.NeighborVO neighbor(Long id, boolean newer) {
        LambdaQueryWrapper<Note> wrapper = new LambdaQueryWrapper<Note>()
                .eq(Note::getStatus, PUBLISHED)
                .eq(Note::getVisibility, NoteVisibility.PUBLIC.name())
                .gt(newer, Note::getId, id)
                .lt(!newer, Note::getId, id)
                .orderBy(newer, true, Note::getId)
                .orderBy(!newer, false, Note::getId)
                .last("LIMIT 1");
        Note neighbor = noteMapper.selectOne(wrapper);
        if (neighbor == null) {
            return null;
        }
        NoteDetailVO.NeighborVO vo = new NoteDetailVO.NeighborVO();
        vo.setId(neighbor.getId());
        vo.setTitle(neighbor.getTitle());
        return vo;
    }

    private NoteVO toVO(Note note) {
        NoteVO vo = new NoteVO();
        vo.setId(note.getId());
        vo.setUserId(note.getUserId());
        vo.setTitle(note.getTitle());
        vo.setTags(splitTags(note.getTags()));
        NoteType type = NoteType.parseOrDefault(note.getNoteType());
        vo.setNoteType(type.name());
        vo.setNoteTypeLabel(type.getLabel());
        vo.setNoteTypeGlyph(type.getGlyph());
        vo.setVisibility(note.getVisibility());
        vo.setWordCount(note.getWordCount());
        vo.setViewCount(note.getViewCount() == null ? 0 : note.getViewCount());
        vo.setStatus(note.getStatus());
        vo.setDate(note.getCreatedAt().toLocalDate().format(DATE_FMT));
        vo.setUpdatedAt(note.getUpdatedAt());
        vo.setVerifiedAt(note.getVerifiedAt());
        String content = note.getContent() == null ? "" : note.getContent();
        /* 摘要优先取「结论」章节：技术笔记最有价值的是结论，而不是开头 */
        vo.setSummary(summarize(content));
        return vo;
    }

    /**
     * 摘要：优先截取 `## 结论` 章节的内容，取不到再退回正文开头。
     * 这样列表里一眼能看到「怎么解决的」，而不是「问题是什么」。
     */
    static String summarize(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String conclusion = section(content, "结论");
        String source = StringUtils.hasText(conclusion) ? conclusion : content;
        /* 去掉 Markdown 标记与换行，避免摘要里出现 ## / ``` / 表格线 */
        String plain = source
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                .replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("`", "")
                .replaceAll("[*_>#]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return plain.length() > SUMMARY_LEN ? plain.substring(0, SUMMARY_LEN) + "……" : plain;
    }

    /** 取指定 `## 标题` 章节的正文（到下一个同级标题为止） */
    private static String section(String content, String heading) {
        String[] lines = content.split("\\R");
        StringBuilder buffer = new StringBuilder();
        boolean inside = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.matches("^#{1,6}\\s*" + heading + "\\s*$")) {
                inside = true;
                continue;
            }
            if (inside && trimmed.matches("^#{1,6}\\s+.*$")) {
                break;
            }
            if (inside) {
                buffer.append(line).append('\n');
            }
        }
        return buffer.toString().trim();
    }

    private static List<String> splitTags(String tags) {
        if (!StringUtils.hasText(tags)) {
            return List.of();
        }
        return Arrays.stream(tags.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String joinTags(List<String> tags) {
        if (tags == null) {
            return "";
        }
        return String.join(",", tags.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    private static int countWords(String content) {
        return content.replaceAll("\\s", "").length();
    }

    private static String requireContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw BusinessExceptionHelper.of("笔记正文不能为空，先写点什么。");
        }
        return content.trim();
    }

    private static String optionalContent(String content) {
        return content == null ? "" : content.trim();
    }
}
