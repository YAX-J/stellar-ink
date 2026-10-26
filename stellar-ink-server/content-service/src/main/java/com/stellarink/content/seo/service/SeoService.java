package com.stellarink.content.seo.service;

/**
 * 站点对外的分发出口：站点地图与 RSS。
 *
 * <p>它们不是「给页面用的读模型」，而是给搜索引擎与订阅器用的另一种视图，
 * 因此不返回 {@code Response<T>}，也不进 Redis 读模型缓存 —— 爬虫与订阅器的
 * 请求量远低于页面，直接查库反而保证「文章一发出去就出现在地图里」。
 */
public interface SeoService {

    /** {@code /sitemap.xml}：公开可索引 URL 的完整清单 */
    String sitemap();

    /** {@code /feed.xml}：文章与公开笔记合并的时间序 RSS */
    String feed();
}
