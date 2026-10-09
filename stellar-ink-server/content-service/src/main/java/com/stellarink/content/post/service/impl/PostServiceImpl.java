package com.stellarink.content.post.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.common.util.Pagination;
import com.stellarink.common.util.WordCount;
import com.stellarink.content.cache.CachedPage;
import com.stellarink.content.cache.ContentCache;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
    private static final String CACHE_NAMESPACE = "post";
    private static final TypeReference<CachedPage<PostVO>> PAGE_CACHE_TYPE = new TypeReference<>() { };

    /**
     * 浏览量计数结果：{@code stellar.view.recorded{kind=post,result=counted|deduped}}。
     *
     * <p>为什么值得单独埋一个指标：浏览闸门是「登录用户按天去重、未登录访客每次计数」，
     * 于是 {@code counted} 与 {@code deduped} 的**比例**就是「回头客 vs 新访问」的近似读数。
     * 如果哪天这个比例突然全是 {@code counted}，通常意味着闸门表没生效（去重失效），
     * 而不是「今天读者变多了」—— 这两件事在数据库里长得一样，只有分开计数才分得开。
     *
     * <p>{@code kind} 区分文章与笔记（笔记在同一张闸门表上，见 {@code NoteServiceImpl}）；
     * 「文章不存在 / 未发射」这类**本来就不该计**的情况刻意不入指标 ——
     * 它们不是「被去重」，混进 deduped 会让去重率虚高。
     */
    private static final String METRIC_VIEW = "stellar.view.recorded";

    /**
     * 点赞结果：{@code stellar.glow.recorded{result=created|duplicate}}。
     *
     * <p>{@code duplicate}（登录用户重复点赞被唯一键挡下）本身就是**正常行为**
     * （用户会连点、前端会重试），但它一旦异常升高，指向的是前端重复提交或按钮状态没更新 ——
     * 这是「数据没错、体验有错」的那一类问题，靠日志看不出来。
     */
    private static final String METRIC_GLOW = "stellar.glow.recorded";

    private static final String TAG_RESULT = "result";
    private static final String TAG_KIND = "kind";
    private static final String KIND_POST = "post";
    private static final String RESULT_COUNTED = "counted";
    private static final String RESULT_DEDUPED = "deduped";
    private static final String RESULT_CREATED = "created";
    private static final String RESULT_DUPLICATE = "duplicate";

    private final PostMapper postMapper;
    private final PostGlowMapper postGlowMapper;
    private final PostViewMapper postViewMapper;
    private final CommentMapper commentMapper;
    private final ContentCache cache;
    private final MeterRegistry meterRegistry;

    @Override
    public IPage<PostVO> page(PostQueryDTO query) {
        Pagination.requireValid(query.getPage(), query.getSize());
        if (!query.isPublishedOnly()
                || (query.getStatus() != null && !Integer.valueOf(1).equals(query.getStatus()))) {
            return loadPublicPage(query);
        }
        String cacheKey = cache.versionedKey(CACHE_NAMESPACE, "page",
                query.getPage(), query.getSize(), query.getYear(), query.getTag(), query.getKeyword(),
                query.getStatus(), query.getOrderBy(), query.isPublishedOnly());
        CachedPage<PostVO> cached = cache.getOrLoad(CACHE_NAMESPACE, cacheKey, PAGE_CACHE_TYPE,
                ContentCache.DEFAULT_TTL, () -> {
            IPage<PostVO> loaded = loadPublicPage(query);
            return CachedPage.from(loaded);
        });
        return cached.toPage();
    }

    private IPage<PostVO> loadPublicPage(PostQueryDTO query) {
        String tag = StringUtils.trimWhitespace(query.getTag());
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>()
                .eq(query.isPublishedOnly(), Post::getStatus, 1)
                .eq(query.getStatus() != null, Post::getStatus, query.getStatus())
                .apply(query.getYear() != null, "YEAR(created_at) = {0}", query.getYear())
                .apply(StringUtils.hasText(tag), "FIND_IN_SET({0}, tags) > 0", tag)
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
        Pagination.requireValid(query.getPage(), query.getSize());
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
        String cacheKey = cache.versionedKey(CACHE_NAMESPACE, "detail", id);
        PostDetailVO cached = cache.get(CACHE_NAMESPACE, cacheKey, PostDetailVO.class);
        if (cached != null) {
            cached.setLiked(likedBy(id));
            return cached;
        }
        Post post = requirePost(id);
        ensureReadable(post);
        PostDetailVO vo = toDetailVO(post);
        if (Integer.valueOf(1).equals(post.getStatus())) {
            vo.setLiked(false);
            cache.put(CACHE_NAMESPACE, cacheKey, vo, ContentCache.DEFAULT_TTL);
        }
        vo.setLiked(likedBy(id));
        return vo;
    }

    private PostDetailVO toDetailVO(Post post) {
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
        vo.setLiked(false);
        vo.setPrev(neighbor(post.getId(), true));
        vo.setNext(neighbor(post.getId(), false));
        return vo;
    }

    @Override
    @Transactional
    public boolean recordView(Long id) {
        Post post = postMapper.selectById(id);
        if (post == null || !Integer.valueOf(1).equals(post.getStatus())) {
            return false;
        }
        boolean counted;
        if (StpUtil.isLogin()) {
            Long userId = AuthHelper.loginId();
            counted = postViewMapper.claimToday(userId);
        } else {
            /* 匿名为公开接口、无身份可依，按访问计数（文档已说明） */
            counted = true;
        }
        if (counted) {
            postMapper.update(null, new LambdaUpdateWrapper<Post>()
                    .eq(Post::getId, id)
                    .setSql("view_count = view_count + 1"));
            cache.evictVersioned(CACHE_NAMESPACE, "detail", id);
        }
        countView(counted);
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
        invalidatePublicCaches();
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
        invalidatePublicCaches();
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
        invalidatePublicCaches();
        log.info("熄灭星体 id={} title={}", id, post.getTitle());
    }

    @Override
    @Transactional
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
        // 计数真的 +1 了吗：登录用户看「状态是否写成功」（applied），未登录访客点了就算一次。
        // duplicate 因此只可能是「登录用户重复点赞被唯一键挡下」，不会是访客造成的。
        boolean counted = applied || !StpUtil.isLogin();
        countRecorded(METRIC_GLOW, counted ? RESULT_CREATED : RESULT_DUPLICATE);
        Integer glow = postMapper.selectById(id).getGlow();
        cache.evictVersioned(CACHE_NAMESPACE, "detail", id);
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

    /**
     * 记一次浏览量结果（带 {@code kind=post}；笔记侧由 {@code NoteServiceImpl} 记 {@code kind=note}）。
     */
    private void countView(boolean counted) {
        Counter.builder(METRIC_VIEW)
                .tag(TAG_KIND, KIND_POST)
                .tag(TAG_RESULT, counted ? RESULT_COUNTED : RESULT_DEDUPED)
                .description("浏览量计数结果：counted=真的计了一次；deduped=同一登录用户当天已计过被去重"
                        + "（kind 区分 post 与 note；不该计的场合不入指标）")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记一次业务结果计数。
     *
     * <p>标签用「业务结果」而不是「成功/失败」：这里四档（counted/deduped/created/duplicate）
     * **没有一个是错误**，它们全是正常业务分支。压成 success/fail 就等于把信息丢掉 ——
     * 而「去重率突然变成 0」「重复点赞突然变多」正是我们要能看见的东西。
     */
    private void countRecorded(String metricName, String result) {
        Counter.builder(metricName)
                .tag(TAG_RESULT, result)
                .description(METRIC_VIEW.equals(metricName)
                        ? "浏览量计数结果：counted=真的计了一次；deduped=同一登录用户当天已计过被去重"
                        : "点赞结果：created=计数 +1（含未登录访客的每次点击）；duplicate=重复点赞被唯一键挡下")
                .register(meterRegistry)
                .increment();
    }

    private void invalidatePublicCaches() {
        cache.invalidate(CACHE_NAMESPACE);
        cache.invalidate("tag");
        cache.invalidate("stats");
        cache.invalidate("comment");
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
        /* 口径统一在 common-core 的 WordCount：剥离 Markdown 标记后计非空白字符 */
        return WordCount.count(content);
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
