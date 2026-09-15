package com.stellarink.content.post.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.content.comment.mapper.CommentMapper;
import com.stellarink.content.comment.pojo.Comment;
import com.stellarink.content.post.mapper.PostGlowMapper;
import com.stellarink.content.post.mapper.PostMapper;
import com.stellarink.content.post.mapper.PostViewMapper;
import com.stellarink.content.post.pojo.Post;
import com.stellarink.content.post.pojo.PostGlow;
import com.stellarink.content.post.service.PostService;
import com.stellarink.sharedmodel.dto.post.PostCreateDTO;
import com.stellarink.sharedmodel.dto.post.PostQueryDTO;
import com.stellarink.sharedmodel.dto.post.PostUpdateDTO;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.vo.post.GlowResultVO;
import com.stellarink.sharedmodel.vo.post.PostDetailVO;
import com.stellarink.sharedmodel.vo.post.PostVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int SUMMARY_LEN = 60;

    private final PostMapper postMapper;
    private final PostGlowMapper postGlowMapper;
    private final PostViewMapper postViewMapper;
    private final CommentMapper commentMapper;

    @Override
    public IPage<PostVO> page(PostQueryDTO query) {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>()
                .eq(query.isPublishedOnly(), Post::getStatus, 1)
                .eq(query.getStatus() != null, Post::getStatus, query.getStatus())
                .apply(query.getYear() != null, "YEAR(created_at) = {0}", query.getYear())
                .like(StringUtils.hasText(query.getTag()), Post::getTags, query.getTag())
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(Post::getTitle, query.getKeyword())
                        .or()
                        .like(Post::getContent, query.getKeyword()));
        /* 排序统一在 applyOrder 里加：MyBatis-Plus 对同一列只保留先加入的排序条件，
         * 若这里先写死 id 倒序，后面的 glow/wordCount 排序会被静默丢弃。 */
        applyOrder(wrapper, query.getOrderBy());

        IPage<Post> page = postMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);
        return page.convert(this::toVO);
    }

    /**
     * 排序：latest 最新（默认，按 id 倒序）/ hottest 最受回望（光芒优先）/ longest 篇幅最长。
     * 一律追加 id 倒序做稳定次序，避免同值结果在分页间跳动。
     */
    private void applyOrder(LambdaQueryWrapper<Post> wrapper, String orderBy) {
        if ("hottest".equalsIgnoreCase(orderBy)) {
            wrapper.orderByDesc(Post::getGlow);
        } else if ("longest".equalsIgnoreCase(orderBy)) {
            wrapper.orderByDesc(Post::getWordCount);
        }
        wrapper.orderByDesc(Post::getId);
    }

    @Override
    public IPage<PostVO> mine(PostQueryDTO query) {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>()
                .eq(query.getStatus() != null, Post::getStatus, query.getStatus())
                .eq(Post::getUserId, AuthHelper.loginId())
                .orderByDesc(Post::getUpdatedAt)
                .orderByDesc(Post::getId);
        IPage<Post> page = postMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);
        return page.convert(this::toVO);
    }

    @Override
    public PostDetailVO detail(Long id) {
        Post post = requirePost(id);
        ensureReadable(post);
        PostDetailVO vo = new PostDetailVO();
        vo.setId(post.getId());
        vo.setUserId(post.getUserId());
        vo.setStatus(post.getStatus());
        vo.setTitle(post.getTitle());
        vo.setContent(post.getContent());
        vo.setTags(splitTags(post.getTags()));
        vo.setWordCount(post.getWordCount());
        vo.setReadMinutes((int) Math.ceil(post.getWordCount() / 400.0));
        vo.setYear(post.getCreatedAt().getYear());
        vo.setDate(post.getCreatedAt().toLocalDate().format(DATE_FMT));
        vo.setGlow(post.getGlow());
        vo.setViewCount(post.getViewCount() == null ? 0 : post.getViewCount());
        vo.setLiked(likedBy(id));
        vo.setPrev(neighbor(post.getId(), true));
        vo.setNext(neighbor(post.getId(), false));
        return vo;
    }

    @Override
    public boolean recordView(Long id) {
        Post post = postMapper.selectById(id);
        if (post == null || !Integer.valueOf(1).equals(post.getStatus())) {
            return false;
        }
        boolean counted;
        if (StpUtil.isLogin()) {
            Long userId = AuthHelper.loginId();
            /* 先读旧值再写：跨天或首次记为「今天这一次要计数」，同一天内重复访问不计数。
             * 顺序必须是先读后写，先写会让判断恒为「今天已计」。 */
            LocalDate lastCounted = postViewMapper.findViewedAt(userId);
            counted = lastCounted == null || !lastCounted.equals(LocalDate.now());
            if (lastCounted == null) {
                postViewMapper.insertToday(userId);
            } else if (counted) {
                postViewMapper.touchToday(userId);
            }
        } else {
            /* 匿名为公开接口、无身份可依，按访问计数（文档已说明） */
            counted = true;
        }
        if (counted) {
            postMapper.update(null, new LambdaUpdateWrapper<Post>()
                    .eq(Post::getId, id)
                    .setSql("view_count = view_count + 1"));
        }
        return counted;
    }

    @Override
    public Long create(PostCreateDTO dto) {
        String content = dto.getStatus() != null && dto.getStatus() == 0
                ? optionalContent(dto.getContent()) : requireContent(dto.getContent());
        LocalDateTime now = LocalDateTime.now();
        Post entity = new Post();
        entity.setUserId(AuthHelper.loginId());
        entity.setTitle(StringUtils.hasText(dto.getTitle()) ? dto.getTitle().trim() : "无题的一夜");
        entity.setContent(content);
        entity.setTags(joinTags(dto.getTags()));
        entity.setWordCount(countWords(content));
        entity.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);
        entity.setGlow(0);
        entity.setViewCount(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        postMapper.insert(entity);
        log.info("发射新星 id={} title={} 字数={} tags={}",
                entity.getId(), entity.getTitle(), entity.getWordCount(), entity.getTags());
        return entity.getId();
    }

    @Override
    public void update(Long id, PostUpdateDTO dto) {
        Post post = requirePost(id);
        ensureEditable(post);
        Integer nextStatus = dto.getStatus() == null ? post.getStatus() : dto.getStatus();
        if (StringUtils.hasText(dto.getTitle())) {
            post.setTitle(dto.getTitle().trim());
        }
        if (dto.getContent() != null) {
            String content = nextStatus != null && nextStatus == 0
                    ? optionalContent(dto.getContent()) : requireContent(dto.getContent());
            post.setContent(content);
            post.setWordCount(countWords(content));
        }
        if (dto.getTags() != null) {
            post.setTags(joinTags(dto.getTags()));
        }
        if (Integer.valueOf(1).equals(nextStatus)) {
            requireContent(post.getContent());
        }
        if (dto.getStatus() != null) {
            post.setStatus(dto.getStatus());
        }
        post.setUpdatedAt(LocalDateTime.now());
        postMapper.updateById(post);
        log.info("更新文章 id={} title={}", id, post.getTitle());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Post post = requirePost(id);
        ensureEditable(post);
        postMapper.deleteById(id);
        /* 连带清掉点赞明细，避免同一篇文章重建后继承旧点赞态 */
        postGlowMapper.delete(new LambdaQueryWrapper<PostGlow>().eq(PostGlow::getPostId, id));
        /* 评论保留审计记录但不再对外展示。 */
        commentMapper.update(null, new LambdaUpdateWrapper<Comment>()
                .eq(Comment::getPostId, id)
                .eq(Comment::getStatus, 1)
                .set(Comment::getStatus, 0)
                .set(Comment::getUpdatedAt, LocalDateTime.now()));
        log.info("熄灭星体 id={} title={}", id, post.getTitle());
    }

    @Override
    public GlowResultVO glow(Long id) {
        Post post = requirePost(id);
        if (!Integer.valueOf(1).equals(post.getStatus())) {
            throw BusinessExceptionHelper.of(ErrorCode.NOT_FOUND, "这颗星还没有发射");
        }
        boolean applied = false;
        if (StpUtil.isLogin()) {
            Long userId = AuthHelper.loginId();
            /* 唯一键 (post_id, user_id) 兜住并发重复点击：插不进去就说明已经赞过 */
            if (!hasGlow(id, userId)) {
                try {
                    PostGlow record = new PostGlow();
                    record.setPostId(id);
                    record.setUserId(userId);
                    record.setCreatedAt(LocalDateTime.now());
                    postGlowMapper.insert(record);
                    applied = true;
                } catch (DuplicateKeyException e) {
                    log.debug("重复补充光芒 postId={} userId={}", id, userId);
                }
            }
            if (applied) {
                postMapper.update(null, new LambdaUpdateWrapper<Post>()
                        .eq(Post::getId, id)
                        .setSql("glow = glow + 1"));
            }
        } else {
            /* 未登录访客没有身份可去重，保持一次点击一次计数 */
            postMapper.update(null, new LambdaUpdateWrapper<Post>()
                    .eq(Post::getId, id)
                    .setSql("glow = glow + 1"));
        }
        Integer glow = postMapper.selectById(id).getGlow();
        log.debug("文章 {} 补充光芒 applied={} -> {}", id, applied, glow);
        return new GlowResultVO(glow, likedBy(id), applied);
    }

    /** 当前登录用户是否已为这篇文章补充过光芒（未登录恒 false） */
    private boolean likedBy(Long postId) {
        return StpUtil.isLogin() && hasGlow(postId, AuthHelper.loginId());
    }

    private boolean hasGlow(Long postId, Long userId) {
        return postGlowMapper.selectCount(new LambdaQueryWrapper<PostGlow>()
                .eq(PostGlow::getPostId, postId)
                .eq(PostGlow::getUserId, userId)) > 0;
    }

    private Post requirePost(Long id) {
        Post post = postMapper.selectById(id);
        if (post == null) {
            throw BusinessExceptionHelper.of(ErrorCode.NOT_FOUND, "这颗星不存在");
        }
        return post;
    }

    /** 相邻星：prev 取更新的一颗，next 取更早的一颗 */
    private PostDetailVO.NeighborVO neighbor(Long id, boolean newer) {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, 1)
                .gt(newer, Post::getId, id)
                .lt(!newer, Post::getId, id)
                .orderBy(newer, true, Post::getId)
                .orderBy(!newer, false, Post::getId)
                .last("LIMIT 1");
        Post neighbor = postMapper.selectOne(wrapper);
        if (neighbor == null) {
            return null;
        }
        PostDetailVO.NeighborVO vo = new PostDetailVO.NeighborVO();
        vo.setId(neighbor.getId());
        vo.setTitle(neighbor.getTitle());
        return vo;
    }

    private PostVO toVO(Post post) {
        PostVO vo = new PostVO();
        vo.setId(post.getId());
        vo.setTitle(post.getTitle());
        vo.setTags(splitTags(post.getTags()));
        vo.setWordCount(post.getWordCount());
        vo.setYear(post.getCreatedAt().getYear());
        vo.setDate(post.getCreatedAt().toLocalDate().format(DATE_FMT));
        vo.setGlow(post.getGlow());
        vo.setViewCount(post.getViewCount() == null ? 0 : post.getViewCount());
        vo.setStatus(post.getStatus());
        vo.setUserId(post.getUserId());
        vo.setUpdatedAt(post.getUpdatedAt());
        String content = post.getContent() == null ? "" : post.getContent();
        vo.setSummary(content.length() > SUMMARY_LEN ? content.substring(0, SUMMARY_LEN) + "……" : content);
        return vo;
    }

    public static List<String> splitTags(String tags) {
        if (!StringUtils.hasText(tags)) {
            return List.of();
        }
        return Arrays.stream(tags.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    static String joinTags(List<String> tags) {
        if (tags == null) {
            return "";
        }
        return String.join(",", tags.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    static int countWords(String content) {
        return content.replaceAll("\\s", "").length();
    }

    static String requireContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw BusinessExceptionHelper.of("正文不能为空，先写点什么再发射。");
        }
        return content.trim();
    }

    static String optionalContent(String content) {
        return content == null ? "" : content.trim();
    }

    private void ensureReadable(Post post) {
        if (Integer.valueOf(1).equals(post.getStatus())) {
            return;
        }
        ensureEditable(post);
    }

    private void ensureEditable(Post post) {
        if (!StpUtil.isLogin()) {
            throw BusinessExceptionHelper.of(ErrorCode.NOT_FOUND, "这颗星不存在");
        }
        Role role = AuthHelper.currentRole();
        if (role == Role.ADMIN
                || (role == Role.AUTHOR && AuthHelper.loginId().equals(post.getUserId()))) {
            return;
        }
        throw BusinessExceptionHelper.of(ErrorCode.FORBIDDEN, "没有权限操作这颗星");
    }
}
