package com.stellarink.sharedmodel.dto.note;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 新建笔记入参。长度上限对齐 note 表列宽（title 200 / content TEXT / tags 200）。
 *
 * <p>缺省值：不传 visibility 即 {@code PUBLIC}（技术笔记默认要被读到），不传 status 即 {@code 0} 草稿。
 * 「没传」与「传错」被刻意区别对待 —— 无法识别的可见性值仍回退 {@code PRIVATE}，
 * 见 {@code NoteVisibility.defaultForCreate}。
 */
@Data
public class NoteCreateDTO {

    @Size(max = 200, message = "标题最多 200 个字。")
    private String title;

    @Size(max = 20000, message = "正文太长了，一次最多 20000 个字。")
    private String content;

    /** 技术栈标签，逗号拼接后存入 VARCHAR(200) */
    @Size(max = 10, message = "标签最多 10 个。")
    private List<@Size(max = 15, message = "单个标签最多 15 个字。")
            @Pattern(regexp = "^[^,]*$", message = "标签不能包含逗号。") String> tags;

    /** FIX / PITFALL / TIL / SCRAP，缺省 FIX */
    private String noteType;

    /** PUBLIC / PRIVATE，缺省 PUBLIC（传了无法识别的值则回退 PRIVATE） */
    private String visibility;

    /** 0 草稿 / 1 发布，缺省 0（草稿） */
    @Min(value = 0, message = "状态只能是 0（草稿）或 1（发布）。")
    @Max(value = 1, message = "状态只能是 0（草稿）或 1（发布）。")
    private Integer status;
}
