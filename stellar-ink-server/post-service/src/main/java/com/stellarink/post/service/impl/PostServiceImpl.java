package com.stellarink.post.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.post.mapper.PostMapper;
import com.stellarink.post.pojo.Post;
import com.stellarink.post.service.PostService;
import com.stellarink.sharedmodel.dto.post.PostCreateDTO;
import com.stellarink.sharedmodel.dto.post.PostQueryDTO;
import com.stellarink.sharedmodel.dto.post.PostUpdateDTO;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.vo.post.PostDetailVO;
import com.stellarink.sharedmodel.vo.post.PostVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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

    private final PostMapper postMapper;

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
                        .like(Post::getContent, query.getKeyword()))
                .orderByDesc(Post::getId);

        IPage<Post> page = postMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);
        return page.convert(this::toVO);
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
        vo.setPrev(neighbor(post.getId(), true));
        vo.setNext(neighbor(post.getId(), false));
        return vo;
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
    public void delete(Long id) {
        Post post = requirePost(id);
        ensureEditable(post);
        postMapper.deleteById(id);
        log.info("熄灭星体 id={} title={}", id, post.getTitle());
    }

    @Override
    public Integer glow(Long id) {
        Post post = requirePost(id);
        if (!Integer.valueOf(1).equals(post.getStatus())) {
            throw BusinessExceptionHelper.of(ErrorCode.NOT_FOUND, "这颗星还没有发射");
        }
        postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, id)
                .setSql("glow = glow + 1"));
        int glow = postMapper.selectById(id).getGlow();
        log.debug("文章 {} 光芒 +1 -> {}", id, glow);
        return glow;
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
