package com.stellarink.content.seo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.content.note.mapper.NoteMapper;
import com.stellarink.content.note.pojo.Note;
import com.stellarink.content.post.mapper.PostMapper;
import com.stellarink.content.post.pojo.Post;
import com.stellarink.content.seo.service.SeoService;
import com.stellarink.sharedmodel.enums.NoteVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 站点地图与 RSS 的生成。
 *
 * <p>三条口径，改动前先读：
 * <ol>
 *   <li><b>只列公开、已发布的内容</b>：文章 {@code status=1}；笔记 {@code status=1 且 visibility=PUBLIC}。
 *       sitemap 里出现一条私有笔记，等于把「只有作者能看」的承诺交给爬虫；</li>
 *   <li><b>绝对链接来自配置</b>（{@code stellar.ink.site.base-url}），不拼请求头 ——
 *       Nginx 后面的 {@code Host}、{@code X-Forwarded-*} 在不同入口（CF / 直连 IP / 本机）
 *       各不相同，拼出来的域名一旦是内网名或 IP，地图与订阅就都在错误域名上；</li>
 *   <li><b>XML 一律转义</b>：标题与摘要里有 {@code & < > " '} 是常态
 *       （「把博客写成一座花园」之外还有报错原文与命令行），不转义会让整份文档失效。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class SeoServiceImpl implements SeoService {

    private static final DateTimeFormatter LAST_MOD = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 站点地图上限：个人博客远到不了；主要是防「哪天数据异常」时生成一份巨大的 XML */
    private static final int SITEMAP_LIMIT = 1000;

    /** RSS 条数：订阅器一般只看最近若干条，太多反而拖慢每次刷新 */
    private static final int FEED_LIMIT = 30;

    private static final int SUMMARY_LEN = 140;

    private static final int PUBLISHED = 1;

    private final PostMapper postMapper;
    private final NoteMapper noteMapper;

    /** 站点对外地址；换域名 / 上测试机时用环境变量 SITE_BASE_URL 覆盖，不改代码 */
    @Value("${stellar.ink.site.base-url:https://stellar.ink}")
    private String baseUrl;

    @Override
    public String sitemap() {
        List<Post> posts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, PUBLISHED)
                .select(Post::getId, Post::getUpdatedAt)
                .orderByDesc(Post::getId)
                .last("LIMIT " + SITEMAP_LIMIT));
        List<Note> notes = noteMapper.selectList(new LambdaQueryWrapper<Note>()
                .eq(Note::getStatus, PUBLISHED)
                .eq(Note::getVisibility, NoteVisibility.PUBLIC.name())
                .select(Note::getId, Note::getUpdatedAt)
                .orderByDesc(Note::getId)
                .last("LIMIT " + SITEMAP_LIMIT));

        StringBuilder xml = new StringBuilder(8192);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        /* 三个固定入口：首页 / 笔记 / 星图。搜索页与个人管理页刻意不列 —— 没有独立内容 */
        appendUrl(xml, siteUrl() + "/", null, "1.0");
        appendUrl(xml, siteUrl() + "/notes", null, "0.8");
        appendUrl(xml, siteUrl() + "/archive", null, "0.6");
        posts.forEach(post -> appendUrl(xml, siteUrl() + "/read/" + post.getId(), post.getUpdatedAt(), "0.7"));
        notes.forEach(note -> appendUrl(xml, siteUrl() + "/note/" + note.getId(), note.getUpdatedAt(), "0.7"));
        xml.append("</urlset>\n");
        return xml.toString();
    }

    @Override
    public String feed() {
        List<FeedItem> items = new ArrayList<>();
        /* 两个来源各取最近 FEED_LIMIT 条再合并排序：合并后仍取前 FEED_LIMIT 条，
         * 所以单边取 FEED_LIMIT 条一定够（合并后的第 N 条不可能来自任一边的第 N+1 条之后） */
        postMapper.selectList(new LambdaQueryWrapper<Post>()
                        .eq(Post::getStatus, PUBLISHED)
                        .select(Post::getId, Post::getTitle, Post::getContent, Post::getCreatedAt, Post::getUpdatedAt)
                        .orderByDesc(Post::getCreatedAt)
                        .last("LIMIT " + FEED_LIMIT))
                .forEach(post -> items.add(new FeedItem(
                        post.getTitle(),
                        siteUrl() + "/read/" + post.getId(),
                        timeOf(post.getCreatedAt(), post.getUpdatedAt()),
                        summaryOf(post.getContent()))));
        noteMapper.selectList(new LambdaQueryWrapper<Note>()
                        .eq(Note::getStatus, PUBLISHED)
                        .eq(Note::getVisibility, NoteVisibility.PUBLIC.name())
                        .select(Note::getId, Note::getTitle, Note::getContent, Note::getCreatedAt, Note::getUpdatedAt)
                        .orderByDesc(Note::getCreatedAt)
                        .last("LIMIT " + FEED_LIMIT))
                .forEach(note -> items.add(new FeedItem(
                        note.getTitle(),
                        siteUrl() + "/note/" + note.getId(),
                        timeOf(note.getCreatedAt(), note.getUpdatedAt()),
                        summaryOf(note.getContent()))));

        items.sort(Comparator.comparing(FeedItem::time).reversed());
        List<FeedItem> latest = items.subList(0, Math.min(FEED_LIMIT, items.size()));

        StringBuilder xml = new StringBuilder(8192);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\">\n");
        xml.append("<channel>\n");
        appendTag(xml, "title", "星笺 · STELLAR INK");
        appendTag(xml, "link", siteUrl() + "/");
        appendTag(xml, "description", "把文章比作星辰的夜间写作与技术笔记博客。");
        appendTag(xml, "language", "zh-CN");
        appendTag(xml, "lastBuildDate", RFC_1123.format(ZonedDateTime.now(ZONE)));
        xml.append("  <atom:link href=\"").append(escape(siteUrl() + "/feed.xml"))
                .append("\" rel=\"self\" type=\"application/rss+xml\" />\n");
        for (FeedItem item : latest) {
            xml.append("  <item>\n");
            appendTag(xml, "title", item.title());
            appendTag(xml, "link", item.link());
            xml.append("    <guid isPermaLink=\"true\">").append(escape(item.link())).append("</guid>\n");
            appendTag(xml, "pubDate", RFC_1123.format(ZonedDateTime.of(item.time(), ZONE)));
            appendTag(xml, "description", item.summary());
            xml.append("  </item>\n");
        }
        xml.append("</channel>\n");
        xml.append("</rss>\n");
        return xml.toString();
    }

    private void appendUrl(StringBuilder xml, String loc, LocalDateTime lastMod, String priority) {
        xml.append("  <url>\n");
        xml.append("    <loc>").append(escape(loc)).append("</loc>\n");
        if (lastMod != null) {
            xml.append("    <lastmod>").append(LAST_MOD.format(lastMod)).append("</lastmod>\n");
        }
        xml.append("    <priority>").append(priority).append("</priority>\n");
        xml.append("  </url>\n");
    }

    /** 输出一个带转义的简单标签；值里出现 XML 特殊字符时靠 escape 兜住 */
    private void appendTag(StringBuilder xml, String name, String value) {
        xml.append("    <").append(name).append('>')
                .append(escape(value))
                .append("</").append(name).append(">\n");
    }

    private String siteUrl() {
        String url = StringUtils.hasText(baseUrl) ? baseUrl.trim() : "https://stellar.ink";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static LocalDateTime timeOf(LocalDateTime createdAt, LocalDateTime updatedAt) {
        return createdAt != null ? createdAt : (updatedAt != null ? updatedAt : LocalDateTime.now());
    }

    /**
     * 订阅里的摘要：压成纯文本的一句话。
     *
     * <p>刻意不复用笔记列表的「结论优先」摘要 —— 那是列表卡片的版式需求；
     * 订阅器的 description 只用来让人判断「要不要点开」，取正文开头即可。
     */
    private static String summaryOf(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return "";
        }
        String text = markdown
                .replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("`([^`]*)`", "$1")
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                .replaceAll("!\\[[^\\]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1")
                .replaceAll("[*_>#~|]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() > SUMMARY_LEN ? text.substring(0, SUMMARY_LEN) + "…" : text;
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /** 合并后的单条订阅项；时间取创建时间（订阅看的是「什么时候发的」） */
    private record FeedItem(String title, String link, LocalDateTime time, String summary) {
    }
}
