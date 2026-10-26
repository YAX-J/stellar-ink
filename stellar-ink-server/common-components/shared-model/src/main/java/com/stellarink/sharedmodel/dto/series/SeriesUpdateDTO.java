package com.stellarink.sharedmodel.dto.series;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 更新系列入参。字段均可空（局部更新），只约束长度。 */
@Data
public class SeriesUpdateDTO {

    @Size(max = 100, message = "系列标题最多 100 个字。")
    private String title;

    @Size(max = 300, message = "系列简介最多 300 个字。")
    private String description;
}
