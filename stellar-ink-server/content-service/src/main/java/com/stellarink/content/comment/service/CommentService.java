package com.stellarink.content.comment.service;

import com.stellarink.sharedmodel.dto.comment.CommentCreateDTO;
import com.stellarink.sharedmodel.vo.comment.CommentVO;

import java.util.List;

public interface CommentService {

    List<CommentVO> list(Long postId);

    CommentVO create(Long postId, CommentCreateDTO dto);

    void delete(Long postId, Long commentId);
}
