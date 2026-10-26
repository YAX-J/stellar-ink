package com.stellarink.content.post.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.content.note.service.NoteService;
import com.stellarink.content.post.service.PostService;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.note.NoteVO;
import com.stellarink.sharedmodel.vo.post.PostVO;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全站搜索：文章与公开笔记各一条出口。
 *
 * <p>两者都按**相关度**排序（标题 3 / 标签 2 / 正文 1，同分按 id 倒序），
 * 而不是以前的 id 倒序 —— 「搜 lettuce 却看到最新一篇」不是搜索，是倒序列表。
 *
 * <p>笔记单独一条 {@code /search/notes} 而不是复用 {@code /notes?keyword=}：
 * 列表接口还有标签/类型筛选与分页语义，搜索语义（相关度、不受 orderBy 影响）不该混进去。
 * 两条都只返回公开内容（笔记由 SQL 里强制 {@code status=1 AND visibility='PUBLIC'}）。
 */
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final PostService postService;
    private final NoteService noteService;

    @GetMapping
    public Response<IPage<PostVO>> search(@RequestParam String keyword,
                                          @RequestParam(required = false, defaultValue = "1") Integer page,
                                          @RequestParam(required = false, defaultValue = "10") Integer size) {
        requireKeyword(keyword);
        return Response.success(postService.search(keyword.trim(), page, size));
    }

    @GetMapping("/notes")
    public Response<IPage<NoteVO>> searchNotes(@RequestParam String keyword,
                                               @RequestParam(required = false, defaultValue = "1") Integer page,
                                               @RequestParam(required = false, defaultValue = "10") Integer size) {
        requireKeyword(keyword);
        return Response.success(noteService.search(keyword.trim(), page, size));
    }

    private static void requireKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            throw BusinessExceptionHelper.of("告诉发射器一个关键词。");
        }
    }
}
