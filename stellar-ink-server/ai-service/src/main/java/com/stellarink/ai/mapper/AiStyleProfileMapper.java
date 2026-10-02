package com.stellarink.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.ai.pojo.AiStyleProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 派生风格画像（{@code ai_style_profile}）的 Mapper。
 *
 * <p>删除记忆时要连它一起清（派生数据不该在「删除」之后还留着）。
 */
@Mapper
public interface AiStyleProfileMapper extends BaseMapper<AiStyleProfile> {
}