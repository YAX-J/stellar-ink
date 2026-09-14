package com.stellarink.sharedmodel.dto.note;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 新建笔记入参。长度上限对齐 note 表列宽（title 200 / content TEXT / tags 200）。
 *
 * <p>缺省值刻意保守：不传 visibility 就是 {@code PRIVATE}，不传 status 就是 {@code 0} 草稿，
 * 避免「随手新建一条笔记」意外公开。
 */
@Data
public class NoteCreateDTO {

    @Size(max = 200, message = "标题最多 200 个字。")
    private String title;

    @Size(max = 20000, message = "正文太长了，一次最多 20000 个字。")
    private String content;

    /** 技术栈标签，逗号拼接后存入 VARCHAR(200) */
    @Size(max = 10, message = "标签最多 10 个。")
    private List<@Size(max = 15, message = "单个标签最多 15 个字。") String> tags;

    /** FIX / PITFALL / TIL / SCRAP，缺省 FIX */
    private String noteType;

    /** PUBLIC / PRIVATE，缺省 PRIVATE */
    private String visibility;

    /** 0 草稿 / 1 发布，缺省 0（草稿） */
    @Min(value = 0, message = "状态只能是 0（草稿）或 1（发布）。")
    @Max(value = 1, message = "状态只能是 0（草稿）或 1（发布）。")
    private Integer status;
}
