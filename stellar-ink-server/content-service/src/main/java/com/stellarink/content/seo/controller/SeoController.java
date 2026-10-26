package com.stellarink.content.seo.controller;

import com.stellarink.content.seo.service.SeoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SEO 出口：站点地图与 RSS。
 *
 * <p>路径固定在站点根（{@code /sitemap.xml}、{@code /feed.xml}），这是搜索引擎与
 * 订阅器唯一认的位置；网关的「GET 全放行」已经覆盖它们，因此这里不需要额外鉴权，
 * 也不能加 —— 爬虫不会带 token。
 *
 * <p>单元是「字符串」而不是 {@code Response<T>}：这两条出口要的是标准 XML 文档，
 * 包一层 {@code {code,msg,data}} 会让爬虫一个字都读不懂。
 */
@RestController
@RequiredArgsConstructor
public class SeoController {

    private final SeoService seoService;

    @GetMapping(value = "/sitemap.xml", produces = "application/xml;charset=UTF-8")
    public String sitemap() {
        return seoService.sitemap();
    }

    @GetMapping(value = "/feed.xml", produces = "application/rss+xml;charset=UTF-8")
    public String feed() {
        return seoService.feed();
    }
}
