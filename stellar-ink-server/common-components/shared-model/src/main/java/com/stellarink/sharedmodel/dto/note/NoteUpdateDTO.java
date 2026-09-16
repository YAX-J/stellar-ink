package com.stellarink.sharedmodel.dto.note;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新笔记入参。字段均可空（局部更新），仅约束长度与取值上限。
 *
 * <p>注意：{@code visibility} 与 {@code status} 显式传值才会变更，
 * 传 null 保持原状 —— 避免编辑正文时把一条公开笔记意外改回私有（或反之）。
 */
@Data
public class NoteUpdateDTO {

    @Size(max = 200, message = "标题最多 200 个字。")
    private String title;

    @Size(max = 20000, message = "正文太长了，一次最多 20000 个字。")
    private String content;

    @Size(max = 10, message = "标签最多 10 个。")
    private List<@Size(max = 15, message = "单个标签最多 15 个字。")
            @Pattern(regexp = "^[^,]*$", message = "标签不能包含逗号。") String> tags;

    private String noteType;

    private String visibility;

    @Min(value = 0, message = "状态只能是 0（草稿）或 1（发布）。")
    @Max(value = 1, message = "状态只能是 0（草稿）或 1（发布）。")
    private Integer status;
}
