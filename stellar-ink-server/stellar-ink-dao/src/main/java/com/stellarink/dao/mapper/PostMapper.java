package com.stellarink.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.dao.entity.PostEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PostMapper extends BaseMapper<PostEntity> {
}
