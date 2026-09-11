package com.stellarink.sharedmodel.dto.echo;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 回声投瓶入参。长度上限对齐 echo 表列宽（nickname 50 / content 500）。
 */
@Data
public class EchoCreateDTO {

    /** 署名，空则匿名 */
    @Size(max = 50, message = "署名最多 50 个字。")
    private String nickname;

    @Size(max = 500, message = "留言最多 500 个字。")
    private String content;
}
