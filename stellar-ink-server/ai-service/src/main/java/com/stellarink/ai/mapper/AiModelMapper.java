package com.stellarink.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.ai.pojo.AiModel;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型库表的 Mapper。
 *
 * <p>刻意用 {@code @Mapper} 而不是启动类上的 {@code @MapperScan}：后者会让
 * {@code @WebMvcTest} 切片把 Mapper 也扫进来（切片里没有 SqlSession），
 * 于是新增一个 Mapper 就能让别人的切片测试起不来。
 */
@Mapper
public interface AiModelMapper extends BaseMapper<AiModel> {
}
