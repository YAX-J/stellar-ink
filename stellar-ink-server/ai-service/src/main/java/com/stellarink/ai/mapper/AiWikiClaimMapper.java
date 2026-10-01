package com.stellarink.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.ai.pojo.AiWikiClaim;
import org.apache.ibatis.annotations.Mapper;

/**
 * Wiki 主张的 Mapper（表 {@code ai_wiki_claim}）。
 *
 * <p>同其它 Mapper：用 {@code @Mapper} 而不是启动类上的 {@code @MapperScan} ——
 * 后者会让 {@code @WebMvcTest} 切片把 Mapper 一起扫进来、要求 SqlSessionFactory。
 */
@Mapper
public interface AiWikiClaimMapper extends BaseMapper<AiWikiClaim> {
}
