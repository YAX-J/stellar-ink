package com.stellarink.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stellarink.common.result.Result;
import com.stellarink.domain.vo.PostVO;
import com.stellarink.service.search.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping
    public Result<IPage<PostVO>> search(@RequestParam String keyword,
                                        @RequestParam(required = false, defaultValue = "1") Integer page,
                                        @RequestParam(required = false, defaultValue = "10") Integer size) {
        return Result.ok(searchService.search(keyword, page, size));
    }
}
