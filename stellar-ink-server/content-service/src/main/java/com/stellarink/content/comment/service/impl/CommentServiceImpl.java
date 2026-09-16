package com.stellarink.content.comment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.content.cache.ContentCache;
import com.stellarink.content.comment.mapper.CommentMapper;
import com.stellarink.content.comment.pojo.Comment;
import com.stellarink.content.comment.service.CommentService;
import com.stellarink.content.post.mapper.PostMapper;
import com.stellarink.content.post.pojo.Post;
import com.stellarink.sharedmodel.dto.comment.CommentCreateDTO;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.vo.comment.CommentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    private static final TypeReference<List<CommentVO>> CACHE_TYPE = new TypeReference<>() { };

    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final ContentCache cache;

    @Override
    public List<CommentVO> list(Long postId) {
        String cacheKey = cache.versionedKey("comment", "post", postId);
        return cache.getOrLoad(cacheKey, CACHE_TYPE, ContentCache.SHORT_TTL,
                () -> loadComments(postId));
    }

    private List<CommentVO> loadComments(Long postId) {
        ensurePublishedPost(postId);
        return commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getPostId, postId)
                .eq(Comment::getStatus, 1)
                .orderByAsc(Comment::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    public CommentVO create(Long postId, CommentCreateDTO dto) {
        // 评论仅需登录读者权限，网关校验之外服务内再复核一次。
        AuthHelper.requireAtLeast(Role.READER);
        ensurePublishedPost(postId);
        if (!StringUtils.hasText(dto.getContent())) {
            throw BusinessExceptionHelper.of("评论内容不能为空。");
        }
        Comment comment = new Comment();
        comment.setPostId(postId);
        comment.setUserId(AuthHelper.loginId());
        comment.setContent(dto.getContent().trim());
        comment.setStatus(1);
        comment.setCreatedAt(LocalDateTime.now());
        comment.setUpdatedAt(comment.getCreatedAt());
        commentMapper.insert(comment);
        cache.invalidate("comment");
        log.info("发表评论 id={} postId={} userId={}", comment.getId(), postId, comment.getUserId());
        return toVO(comment);
    }

    @Override
    public void delete(Long postId, Long commentId) {
        AuthHelper.requireAtLeast(Role.READER);
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || !postId.equals(comment.getPostId())
                || !Integer.valueOf(1).equals(comment.getStatus())) {
            throw BusinessExceptionHelper.of(ErrorCode.NOT_FOUND, "这条评论不存在。");
        }
        if (AuthHelper.currentRole() != Role.ADMIN
                && !AuthHelper.loginId().equals(comment.getUserId())) {
            throw BusinessExceptionHelper.of(ErrorCode.FORBIDDEN, "不能删除别人的评论。");
        }
        commentMapper.update(null, new LambdaUpdateWrapper<Comment>()
                .eq(Comment::getId, commentId)
                .set(Comment::getStatus, 0)
                .set(Comment::getUpdatedAt, LocalDateTime.now()));
        cache.invalidate("comment");
        log.info("删除评论 id={} postId={} userId={}", commentId, postId, comment.getUserId());
    }

    private void ensurePublishedPost(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || !Integer.valueOf(1).equals(post.getStatus())) {
            throw BusinessExceptionHelper.of(ErrorCode.NOT_FOUND, "这颗星不存在。");
        }
    }

    private CommentVO toVO(Comment comment) {
        CommentVO vo = new CommentVO();
        vo.setId(comment.getId());
        vo.setPostId(comment.getPostId());
        vo.setUserId(comment.getUserId());
        vo.setContent(comment.getContent());
        vo.setCreatedAt(comment.getCreatedAt());
        return vo;
    }
}
