package com.stellarink.content.post.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.stellarink.content.cache.ContentCache;
import com.stellarink.content.post.mapper.PostMapper;
import com.stellarink.content.post.pojo.Post;
import com.stellarink.content.post.service.TagService;
import com.stellarink.sharedmodel.vo.post.TagVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private static final TypeReference<List<TagVO>> CACHE_TYPE = new TypeReference<>() { };

    private final PostMapper postMapper;
    private final ContentCache cache;

    @Override
    public List<TagVO> listWithCount() {
        String cacheKey = cache.versionedKey("tag", "list");
        return cache.getOrLoad(cacheKey, CACHE_TYPE, ContentCache.LONG_TTL, this::loadTags);
    }

    private List<TagVO> loadTags() {
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
