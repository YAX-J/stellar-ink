package com.stellarink.content.comment.controller;

import com.stellarink.content.comment.service.CommentService;
import com.stellarink.sharedmodel.dto.comment.CommentCreateDTO;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.comment.CommentVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/posts/{postId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /** 公开读取已发布文章的评论。 */
    @GetMapping
    public Response<List<CommentVO>> list(@PathVariable Long postId) {
        return Response.success(commentService.list(postId));
    }

    /** 登录用户发表评论。 */
    @PostMapping
    public Response<CommentVO> create(@PathVariable Long postId,
                                      @Valid @RequestBody CommentCreateDTO dto) {
        return Response.success(commentService.create(postId, dto));
    }

    /** 评论作者或站长删除评论。 */
    @DeleteMapping("/{commentId}")
    public Response<Void> delete(@PathVariable Long postId, @PathVariable Long commentId) {
        commentService.delete(postId, commentId);
        return Response.success();
    }
}
