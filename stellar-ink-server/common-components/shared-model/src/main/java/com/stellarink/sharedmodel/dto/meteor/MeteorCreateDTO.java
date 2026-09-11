package com.stellarink.sharedmodel.dto.meteor;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 流星碎片入参。长度上限对齐 meteor.content VARCHAR(500)。
 */
@Data
public class MeteorCreateDTO {

    @Size(max = 500, message = "流星碎片最多 500 个字。")
    private String content;
}
