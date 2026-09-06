package com.stellarink.link.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 友链（友邻星座）
 */
@Data
@TableName("link")
public class Link {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String url;

    private String description;

    /** 0 待确认 / 1 已接入 */
    private Integer status;

    private LocalDateTime createdAt;
}
