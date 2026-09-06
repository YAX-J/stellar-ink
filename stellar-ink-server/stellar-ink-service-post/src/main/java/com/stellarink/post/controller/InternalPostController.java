package com.stellarink.post.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.common.result.Result;
import com.stellarink.post.entity.PostEntity;
import com.stellarink.post.mapper.PostMapper;
import com.stellarink.post.vo.PostSummaryVO;
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
    public Result<List<PostSummaryVO>> summary() {
        return Result.ok(postMapper.selectList(new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getStatus, 1))
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
