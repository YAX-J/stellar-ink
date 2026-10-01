package com.stellarink.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.ai.pojo.AiCallLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 调用账的 Mapper（表 {@code ai_call_log}）。
 *
 * <p>同 {@code AiModelMapper}：用 {@code @Mapper} 而不是启动类上的 {@code @MapperScan}，
 * 否则 {@code @WebMvcTest} 切片会把 Mapper 一起扫进来、要求 SqlSessionFactory。
 */
@Mapper
public interface AiCallLogMapper extends BaseMapper<AiCallLog> {
}
