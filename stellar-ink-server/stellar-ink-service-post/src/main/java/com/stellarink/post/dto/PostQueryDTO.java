package com.stellarink.post.dto;

import lombok.Data;

/**
 * 分页与过滤查询参数（文章列表 / 星图 / 光谱共用）
 */
@Data
public class PostQueryDTO {

    private Integer page = 1;

    private Integer size = 10;

    /** 按年份过滤，空为全部 */
    private Integer year;

    /** 按标签过滤 */
    private String tag;

    /** 标题/内容关键字 */
    private String keyword;

    /** 0 草稿 / 1 已发布，空为全部（管理视角） */
    private Integer status;

    /** 公开接口固定只看已发布 */
    private boolean publishedOnly = true;
}
