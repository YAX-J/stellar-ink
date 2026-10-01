package com.stellarink.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.ai.pojo.AiWikiEntityMention;
import org.apache.ibatis.annotations.Mapper;

/**
 * Wiki 知识图（{@code deploy/sql/14_ai_wiki_entity.sql}）的 Mapper。
 *
 * <p>用 @Mapper 而不是启动类上的 @MapperScan —— 后者会让 @WebMvcTest 切片
 * 把 Mapper 一起扫进来、要求 SqlSessionFactory。
 * 也**不要**写成嵌套接口：类路径扫描默认只认顶层类，嵌在里面的接口不会被注册，
 * 表现是「启动就报找不到 bean」，而错误信息指向的是注入点而不是这里。
 */
@Mapper
public interface AiWikiEntityMentionMapper extends BaseMapper<AiWikiEntityMention> {
}