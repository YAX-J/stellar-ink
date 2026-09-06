package com.stellarink.echo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 回声漂流瓶留言
 */
@Data
@TableName("echo")
public class EchoEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String nickname;

    private String content;

    private LocalDateTime createdAt;
}
