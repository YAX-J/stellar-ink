package com.stellarink.post.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.post.service.PostService;
import com.stellarink.sharedmodel.dto.post.PostQueryDTO;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.post.PostVO;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final PostService postService;

    @GetMapping
    public Response<IPage<PostVO>> search(@RequestParam String keyword,
                                          @RequestParam(required = false, defaultValue = "1") Integer page,
                                          @RequestParam(required = false, defaultValue = "10") Integer size) {
        if (!StringUtils.hasText(keyword)) {
            throw BusinessExceptionHelper.of("告诉发射器一个关键词。");
        }
        PostQueryDTO query = new PostQueryDTO();
        query.setPage(page);
        query.setSize(size);
        query.setKeyword(keyword);
        return Response.success(postService.page(query));
    }
}
