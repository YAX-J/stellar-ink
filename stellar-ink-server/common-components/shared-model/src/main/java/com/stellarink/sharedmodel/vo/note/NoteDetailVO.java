package com.stellarink.sharedmodel.vo.note;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 笔记详情。
 *
 * <p>不带点赞字段：笔记一期不做点赞（私有笔记点赞无意义）。
 * {@code prev/next} 只在「公开已发布」范围内取邻居，私有笔记不参与串接，避免通过相邻导航泄漏存在性。
 */
@Data
public class NoteDetailVO {

    private Long id;

    private Long userId;

    private Integer status;

    private String title;

    private String content;

    private List<String> tags;

    private String noteType;

    private String noteTypeLabel;

    private String noteTypeGlyph;

    private String visibility;

    private Integer wordCount;

    private Integer readMinutes;

    private Integer viewCount;

    /** yyyy-MM-dd */
    private String date;

    private LocalDateTime verifiedAt;

    /** UNVERIFIED / EXPIRED / FRESH */
    private String reviewState;

    /** 下次应复核的时间；从未验证时为 null */
    private LocalDateTime reviewDueAt;

    private NeighborVO prev;

    private NeighborVO next;

    @Data
    public static class NeighborVO {
        private Long id;
        private String title;
    }
}
