package com.stellarink.post.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stellarink.common.result.Result;
import com.stellarink.post.dto.PostCreateDTO;
import com.stellarink.post.dto.PostQueryDTO;
import com.stellarink.post.dto.PostUpdateDTO;
import com.stellarink.post.vo.PostDetailVO;
import com.stellarink.post.vo.PostVO;
import com.stellarink.post.service.PostService;
import lombok.RequiredArgsConstructor;
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
    public Result<IPage<PostVO>> page(@RequestParam(required = false) Integer page,
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
        return Result.ok(postService.page(query));
    }

    @GetMapping("/{id}")
    public Result<PostDetailVO> detail(@PathVariable Long id) {
        return Result.ok(postService.detail(id));
    }

    /** 执笔舱发射（网关鉴权） */
    @PostMapping
    public Result<Map<String, Long>> create(@RequestBody PostCreateDTO dto) {
        return Result.ok(Map.of("id", postService.create(dto)));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody PostUpdateDTO dto) {
        postService.update(id, dto);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        postService.delete(id);
        return Result.ok();
    }

    /** 为这颗星补充光芒（读者可点，公开） */
    @PostMapping("/{id}/glow")
    public Result<Map<String, Integer>> glow(@PathVariable Long id) {
        return Result.ok(Map.of("glow", postService.glow(id)));
    }
}
