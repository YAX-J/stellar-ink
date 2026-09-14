package com.stellarink.sharedmodel.vo.post;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 补充光芒结果：liked 表示当前用户对这篇文章的点赞态
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GlowResultVO {

    /** 最新的光芒总数（以 post_glow 明细为准） */
    private Integer glow;

    /** 当前用户是否已补充过光芒（未登录恒为 false） */
    private Boolean liked;

    /** 本次是否真正计入（false 表示重复点击，未重复计数） */
    private Boolean applied;
}
