package com.stellarink.post.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.post.service.PostService;
import com.stellarink.sharedmodel.dto.post.PostCreateDTO;
import com.stellarink.sharedmodel.dto.post.PostQueryDTO;
import com.stellarink.sharedmodel.dto.post.PostUpdateDTO;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.post.PostDetailVO;
import com.stellarink.sharedmodel.vo.post.PostVO;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    /** 星图 / 星尘卡片 / 长卷列表 */
    @GetMapping
    public Response<IPage<PostVO>> page(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) Integer year,
                                        @RequestParam(required = false) String tag,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) Integer status) {
        PostQueryDTO query = new PostQueryDTO();
        if (page != null) query.setPage(page);
        if (size != null) query.setSize(size);
        query.setYear(year);
        query.setTag(tag);
        query.setKeyword(keyword);
        query.setStatus(status);
        query.setPublishedOnly(status == null);
        return Response.success(postService.page(query));
    }

    @GetMapping("/{id}")
    public Response<PostDetailVO> detail(@PathVariable Long id) {
        return Response.success(postService.detail(id));
    }

    /** 执笔舱发射（网关鉴权） */
    @PostMapping
    public Response<Map<String, Long>> create(@RequestBody PostCreateDTO dto) {
        return Response.success(Map.of("id", postService.create(dto)));
    }

    @PutMapping("/{id}")
    public Response<Void> update(@PathVariable Long id, @RequestBody PostUpdateDTO dto) {
        postService.update(id, dto);
        return Response.success();
    }

    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        postService.delete(id);
        return Response.success();
    }

    /** 为这颗星补充光芒（读者可点，公开） */
    @PostMapping("/{id}/glow")
    public Response<Map<String, Integer>> glow(@PathVariable Long id) {
        return Response.success(Map.of("glow", postService.glow(id)));
    }
}
