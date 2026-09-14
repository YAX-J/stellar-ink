package com.stellarink.content.note.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 技术笔记（标本）。
 *
 * <p>与 {@code Post}（文章/星）是两张独立表：文章重文笔、天然公开；
 * 笔记结构化、可私有、会过期（{@code verifiedAt}）。
 */
@Data
@TableName("note")
public class Note {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    /** 正文（Markdown），结构靠 `## 现象/环境/排查/结论/参考` 章节表达 */
    private String content;

    /** 技术栈标签，逗号分隔 */
    private String tags;

    /** FIX / PITFALL / TIL / SCRAP */
    private String noteType;

    /** PUBLIC / PRIVATE */
    private String visibility;

    /** 0 草稿 / 1 已发布 */
    private Integer status;

    private Integer wordCount;

    /** 浏览量（仅公开笔记计数） */
    private Integer viewCount;

    /** 上次验证结论仍有效的时间，null 表示从未验证 */
    private LocalDateTime verifiedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
