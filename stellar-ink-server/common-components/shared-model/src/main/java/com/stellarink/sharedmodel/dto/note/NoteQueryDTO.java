package com.stellarink.sharedmodel.dto.note;

import lombok.Data;

/**
 * 笔记查询参数。
 *
 * <p>{@code visibility} 只对「我的笔记」（{@code GET /notes/mine}）有效；
 * 公开列表 {@code GET /notes} 会忽略它并在服务端强制 {@code status=1 + visibility=PUBLIC}，
 * 避免出现「前端传 visibility=PRIVATE 就能读到别人私有笔记」这种口子。
 */
@Data
public class NoteQueryDTO {

    private Integer page = 1;

    private Integer size = 10;

    /** 技术栈标签，子串匹配（与文章 tag 语义一致） */
    private String tag;

    /** 笔记类型：FIX / PITFALL / TIL / SCRAP */
    private String noteType;

    /** 标题/正文关键字 */
    private String keyword;

    /** 0 草稿 / 1 已发布，空为全部（仅 /notes/mine 有效） */
    private Integer status;

    /** PUBLIC / PRIVATE，空为全部（仅 /notes/mine 有效） */
    private String visibility;

    /** DUE / UNVERIFIED / EXPIRED / FRESH（仅 /notes/review 有效，默认 DUE） */
    private String reviewState;

    /** 排序：latest 最新（默认）/ hottest 最多浏览 / longest 篇幅最长 */
    private String orderBy;

    /** 是否只查公开已发布（公开列表固定 true，我的笔记固定 false） */
    private boolean publicOnly = true;
}
