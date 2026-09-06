package com.stellarink.post.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.post.mapper.PostMapper;
import com.stellarink.post.pojo.Post;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.post.PostSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务间内部接口：仅供 stats-service 通过服务发现调用。
 * 网关未配置 /internal/** 路由，外部无法直接访问。
 */
@RestController
@RequestMapping("/internal/posts")
@RequiredArgsConstructor
public class InternalPostController {

    private final PostMapper postMapper;

    @GetMapping("/summary")
    public Response<List<PostSummaryVO>> summary() {
        return Response.success(postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, 1))
                .stream().map(p -> {
                    PostSummaryVO vo = new PostSummaryVO();
                    vo.setId(p.getId());
                    vo.setWordCount(p.getWordCount());
                    vo.setTags(p.getTags());
                    vo.setCreatedAt(p.getCreatedAt());
                    return vo;
                }).toList());
    }
}
