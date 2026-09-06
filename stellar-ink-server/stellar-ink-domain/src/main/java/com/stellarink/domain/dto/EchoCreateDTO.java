package com.stellarink.domain.dto;

import lombok.Data;

@Data
public class EchoCreateDTO {

    /** 署名，空则匿名 */
    private String nickname;

    private String content;
}
