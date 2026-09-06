package com.stellarink.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EchoVO {

    private Long id;

    private String nickname;

    private String content;

    private LocalDateTime createdAt;
}
