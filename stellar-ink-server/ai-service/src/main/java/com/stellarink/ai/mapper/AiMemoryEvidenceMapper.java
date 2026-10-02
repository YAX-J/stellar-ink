package com.stellarink.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.ai.pojo.AiMemoryEvidence;
import org.apache.ibatis.annotations.Mapper;

/**
 * 记忆证据（{@code ai_memory_evidence}）的 Mapper。
 *
 * <p>证据单独一张表：把出处塞进记忆行的一个字段里，就没法回答「这条记忆靠哪几句话立起来」。
 */
@Mapper
public interface AiMemoryEvidenceMapper extends BaseMapper<AiMemoryEvidence> {
}