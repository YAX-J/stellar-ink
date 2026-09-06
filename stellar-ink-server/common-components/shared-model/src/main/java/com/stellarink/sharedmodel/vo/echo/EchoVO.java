package com.stellarink.sharedmodel.vo.echo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EchoVO {

    private Long id;

    private String nickname;

    private String content;

    private LocalDateTime createdAt;
}
