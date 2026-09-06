package com.stellarink.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.post.pojo.Post;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PostMapper extends BaseMapper<Post> {
}
