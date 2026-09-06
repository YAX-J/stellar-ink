package com.stellarink.sharedmodel.vo.link;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class LinkVO {

    private Long id;

    private String name;

    private String url;

    private String description;

    /** 0 待确认 / 1 已接入 */
    private Integer status;

    private LocalDateTime createdAt;
}
