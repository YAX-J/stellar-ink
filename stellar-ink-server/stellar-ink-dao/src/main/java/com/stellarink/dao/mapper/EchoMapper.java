package com.stellarink.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.dao.entity.EchoEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EchoMapper extends BaseMapper<EchoEntity> {
}
