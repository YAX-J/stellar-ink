package com.stellarink.api.controller;

import com.stellarink.common.result.Result;
import com.stellarink.domain.vo.TagVO;
import com.stellarink.service.tag.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    /** 光谱 */
    @GetMapping
    public Result<List<TagVO>> list() {
        return Result.ok(tagService.listWithCount());
    }
}
