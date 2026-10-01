package com.stellarink.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.ai.pojo.AiWikiTopicEvidence;
import org.apache.ibatis.annotations.Mapper;

/**
 * 主题（{@code deploy/sql/15_ai_wiki_topic.sql}）的 Mapper。
 *
 * <p>顶层接口 + @Mapper：嵌套接口不会被类路径扫描注册，
 * 而报错会指向注入点而不是那里。
 */
@Mapper
public interface AiWikiTopicEvidenceMapper extends BaseMapper<AiWikiTopicEvidence> {
}