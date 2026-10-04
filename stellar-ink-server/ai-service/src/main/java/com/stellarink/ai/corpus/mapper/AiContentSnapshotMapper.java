package com.stellarink.ai.corpus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.ai.corpus.pojo.AiContentSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 语料投影 Mapper。
 *
 * <p>顶层接口 + {@code @Mapper}（不用启动类上的 {@code @MapperScan}）：嵌套接口不会被类路径扫描注册，
 * 而 {@code @MapperScan} 会把 Mapper 一起塞进 {@code @WebMvcTest} 切片、要求 {@code SqlSessionFactory} ——
 * 详见 {@code AiServiceApplication} 的说明。
 *
 * <p>只访问 `ai_content_snapshot`（AI 域自己的表）。业务表 `post`/`note` 一律不碰。
 */
@Mapper
public interface AiContentSnapshotMapper extends BaseMapper<AiContentSnapshot> {
}
