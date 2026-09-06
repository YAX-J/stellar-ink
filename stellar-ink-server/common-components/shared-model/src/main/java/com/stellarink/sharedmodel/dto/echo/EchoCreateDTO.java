package com.stellarink.sharedmodel.dto.echo;

import lombok.Data;

@Data
public class EchoCreateDTO {

    /** 署名，空则匿名 */
    private String nickname;

    private String content;
}
