package com.stellarink.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class PostCreateDTO {

    private String title;

    private String content;

    /** 标签列表，服务端以逗号分隔存储 */
    private List<String> tags;

    /** 0 草稿 / 1 发布，缺省发布 */
    private Integer status;
}
