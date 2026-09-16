package com.stellarink.sharedmodel.vo.link;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class LinkVO {

    private Long id;

    private String name;

    private String url;

    private String description;

    /** 0 待审核 / 1 已接入 / 2 已驳回 */
    private Integer status;

    private LocalDateTime createdAt;
}
