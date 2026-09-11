package com.stellarink.sharedmodel.dto.link;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 友链申请入参。长度上限对齐 link 表列宽（name 100 / url 200 / description 300）。
 */
@Data
public class LinkApplyDTO {

    @Size(max = 100, message = "站点名最多 100 个字。")
    private String name;

    /**
     * 安全：白名单协议。不加此约束时 javascript:/data: 等可被当作外链存储，
     * 前端一旦渲染成 href 即成为 stored XSS。
     */
    @Size(max = 200, message = "站点地址最多 200 个字符。")
    @Pattern(regexp = "^https?://\\S+$", message = "站点地址必须以 http:// 或 https:// 开头。")
    private String url;

    @Size(max = 300, message = "一句话介绍最多 300 个字。")
    private String description;
}
