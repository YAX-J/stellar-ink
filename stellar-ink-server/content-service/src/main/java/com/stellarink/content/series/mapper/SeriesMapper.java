package com.stellarink.content.series.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.content.series.pojo.Series;
import org.apache.ibatis.annotations.Mapper;

/** 系列表；只被 content-service 读写 */
@Mapper
public interface SeriesMapper extends BaseMapper<Series> {
}
