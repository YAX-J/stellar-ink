package com.stellarink.post.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.post.mapper.PostMapper;
import com.stellarink.post.pojo.Post;
import com.stellarink.post.service.TagService;
import com.stellarink.sharedmodel.vo.post.TagVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private final PostMapper postMapper;

    @Override
    public List<TagVO> listWithCount() {
        List<Post> posts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, 1)
                .select(Post::getTags));
        Map<String, Long> counts = new LinkedHashMap<>();
        posts.stream()
                .map(Post::getTags)
                .forEach(tags -> PostServiceImpl.splitTags(tags)
                        .forEach(tag -> counts.merge(tag, 1L, Long::sum)));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new TagVO(e.getKey(), e.getValue()))
                .toList();
    }
}
