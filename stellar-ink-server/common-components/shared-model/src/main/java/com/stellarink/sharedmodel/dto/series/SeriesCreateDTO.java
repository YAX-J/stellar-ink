package com.stellarink.sharedmodel.dto.series;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新建系列入参。列宽对齐 series 表：title 100 / description 300 */
@Data
public class SeriesCreateDTO {

    @NotBlank(message = "系列得有个名字。")
    @Size(max = 100, message = "系列标题最多 100 个字。")
    private String title;

    @Size(max = 300, message = "系列简介最多 300 个字。")
    private String description;
}
