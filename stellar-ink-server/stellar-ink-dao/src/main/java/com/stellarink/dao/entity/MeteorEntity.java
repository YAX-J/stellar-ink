package com.stellarink.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流星备忘录（碎片）
 */
@Data
@TableName("meteor")
public class MeteorEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String content;

    private LocalDateTime createdAt;
}
