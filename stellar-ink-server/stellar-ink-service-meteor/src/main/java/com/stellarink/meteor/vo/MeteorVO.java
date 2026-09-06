package com.stellarink.meteor.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MeteorVO {

    private Long id;

    private String content;

    private LocalDateTime createdAt;
}
