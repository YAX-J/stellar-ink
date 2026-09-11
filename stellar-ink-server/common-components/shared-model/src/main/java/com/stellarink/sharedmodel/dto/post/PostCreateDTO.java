package com.stellarink.sharedmodel.dto.post;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发射入参。长度上限对齐 post 表列宽（title 200 / content TEXT / tags 200）。
 */
@Data
public class PostCreateDTO {

    @Size(max = 200, message = "标题最多 200 个字。")
    private String title;

    /** 正文上限按 utf8mb4 下 TEXT(65535 字节) 反推，留足余量 */
    @Size(max = 20000, message = "正文太长了，一次最多 20000 个字。")
    private String content;

    /** 标签会以逗号拼接后存入 VARCHAR(200)，故限制个数与单标签长度 */
    @Size(max = 10, message = "标签最多 10 个。")
    private List<@Size(max = 15, message = "单个标签最多 15 个字。") String> tags;

    /** 0 草稿 / 1 发布，缺省发布 */
    @Min(value = 0, message = "状态只能是 0（草稿）或 1（发布）。")
    @Max(value = 1, message = "状态只能是 0（草稿）或 1（发布）。")
    private Integer status;
}
