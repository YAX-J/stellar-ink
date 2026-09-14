package com.stellarink.sharedmodel.vo.note;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 笔记列表项（技术栈热度 / 我的笔记列表共用）。
 */
@Data
public class NoteVO {

    private Long id;

    private Long userId;

    private String title;

    /** 摘要：正文前 60 字 */
    private String summary;

    /** 技术栈标签 */
    private List<String> tags;

    /** FIX / PITFALL / TIL / SCRAP */
    private String noteType;

    /** 类型中文名，前端免维护映射 */
    private String noteTypeLabel;

    /** 类型符号，前端免维护映射 */
    private String noteTypeGlyph;

    /** PUBLIC / PRIVATE（公开列表恒为 PUBLIC；我的笔记可能是两者之一） */
    private String visibility;

    private Integer wordCount;

    private Integer viewCount;

    /** 0 草稿 / 1 已发布 */
    private Integer status;

    /** yyyy-MM-dd（创建日期） */
    private String date;

    private LocalDateTime updatedAt;

    /** 上次验证结论仍有效的时间，null 表示未验证过 */
    private LocalDateTime verifiedAt;
}
