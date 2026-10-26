package com.stellarink.sharedmodel.dto.post;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新入参。字段均可空（局部更新），仅约束长度与取值上限。
 */
@Data
public class PostUpdateDTO {

    @Size(max = 200, message = "标题最多 200 个字。")
    private String title;

    @Size(max = 20000, message = "正文太长了，一次最多 20000 个字。")
    private String content;

    @Size(max = 10, message = "标签最多 10 个。")
    private List<@Size(max = 15, message = "单个标签最多 15 个字。")
            @Pattern(regexp = "^[^,]*$", message = "标签不能包含逗号。") String> tags;

    @Min(value = 0, message = "状态只能是 0（草稿）或 1（发布）。")
    @Max(value = 1, message = "状态只能是 0（草稿）或 1（发布）。")
    private Integer status;

    /**
     * 所属系列：{@code null} = 不修改；{@code 0} = 移出系列；{@code >0} = 挂到自己的系列。
     *
     * <p>用 0 作「移出」哨兵而不是靠 null：null 已经承担了「局部更新时别动它」的语义，
     * 两者混用会让「我不想改系列」和「我要清空系列」变成同一个请求。
     */
    private Long seriesId;

    /** 系列内序号；挂载时不传则追加到末尾，已在本系列且不传则保持原序号 */
    @Min(value = 1, message = "系列序号从 1 开始。")
    private Integer seriesOrder;
}
