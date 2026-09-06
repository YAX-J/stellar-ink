package com.stellarink.post.controller;

import com.stellarink.post.service.TagService;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.post.TagVO;
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
    public Response<List<TagVO>> list() {
        return Response.success(tagService.listWithCount());
    }
}
