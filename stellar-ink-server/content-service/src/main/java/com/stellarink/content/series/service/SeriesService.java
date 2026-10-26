package com.stellarink.content.series.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stellarink.sharedmodel.dto.series.SeriesCreateDTO;
import com.stellarink.sharedmodel.dto.series.SeriesUpdateDTO;
import com.stellarink.sharedmodel.vo.series.SeriesBriefVO;
import com.stellarink.sharedmodel.vo.series.SeriesDetailVO;
import com.stellarink.sharedmodel.vo.series.SeriesVO;

/**
 * 系列 / 专栏。
 *
 * <p>三条口径（与文章/笔记的可见性保持一致）：
 * <ol>
 *   <li><b>读是公开的</b>：列表、详情、计数都只算「已发布文章 + 公开已发布笔记」，
 *       草稿与私有笔记不进任何对外数字；</li>
 *   <li><b>写只归创建者</b>：只有系列创建者能改名/改简介/删除；</li>
 *   <li><b>篇目归属由内容表决定</b>：挂载发生在文章/笔记的保存里（{@code seriesId}），
 *       系列本身不维护成员表 —— 内容删除时不会留下悬挂成员。</li>
 * </ol>
 */
public interface SeriesService {

    IPage<SeriesVO> page(int page, int size);

    SeriesDetailVO detail(Long id);

    Long create(SeriesCreateDTO dto);

    void update(Long id, SeriesUpdateDTO dto);

    /** 删除系列：先把篇目移出（series_id 置空），再删系列本身 */
    void delete(Long id);

    /**
     * 挂载前的校验：{@code null} 表示「不修改」，{@code 0} 表示「移出系列」，
     * 其余必须是当前登录用户自己的系列（不存在 400 / 不是自己的 403）。
     */
    void assertAttachable(Long seriesId);

    /** 系列内下一个序号：取文章与笔记两个来源的最大值 + 1 */
    int nextOrder(Long seriesId);

    /**
     * 阅读页的系列徽章。
     *
     * <p>当前篇目不在公开篇目里（草稿、私有笔记）时 {@code index} 为 {@code null}，
     * 前端只显示「共 N 篇」而不编造位置。
     */
    SeriesBriefVO brief(Long seriesId, Long contentId, String kind);
}
