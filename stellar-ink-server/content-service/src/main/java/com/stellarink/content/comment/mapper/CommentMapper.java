package com.stellarink.content.comment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.content.comment.pojo.Comment;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
}
