package com.stellarink.echo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.echo.entity.EchoEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EchoMapper extends BaseMapper<EchoEntity> {
}
