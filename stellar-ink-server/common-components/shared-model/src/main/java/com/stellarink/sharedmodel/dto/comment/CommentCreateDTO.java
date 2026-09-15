package com.stellarink.sharedmodel.dto.comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 文章评论入参。 */
@Data
public class CommentCreateDTO {

    /** 评论正文。 */
    @NotBlank(message = "评论内容不能为空。")
    @Size(max = 1000, message = "评论最多 1000 个字。")
    private String content;
}
