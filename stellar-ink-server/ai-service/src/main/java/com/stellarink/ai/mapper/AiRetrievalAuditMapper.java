package com.stellarink.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.ai.pojo.AiRetrievalAudit;
import org.apache.ibatis.annotations.Mapper;

/**
 * 检索审计（{@code deploy/sql/17_ai_retrieval_audit.sql}）的 Mapper。
 *
 * <p>顶层接口 + @Mapper：嵌套接口不会被类路径扫描注册，而报错会指向注入点而不是那里。
 */
@Mapper
public interface AiRetrievalAuditMapper extends BaseMapper<AiRetrievalAudit> {
}