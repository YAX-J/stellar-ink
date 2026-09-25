package com.stellarink.sharedmodel.dto.ai;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 把模型库里的某一条**应用到某个角色**（面板上下拉框选完之后提交这个）。
 *
 * <p>只传 id，不传端点与密钥：这样「角色当前在用哪个模型」永远只有一处事实，
 * 面板也不会因为表单字段不全而写进半份配置。
 */
@Data
public class AiModelBindDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "modelId 不能为空")
    private Long modelId;
}
