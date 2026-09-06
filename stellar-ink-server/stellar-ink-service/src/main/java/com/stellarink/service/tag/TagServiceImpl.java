package com.stellarink.service.tag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.dao.entity.PostEntity;
import com.stellarink.dao.mapper.PostMapper;
import com.stellarink.domain.enums.PostStatus;
import com.stellarink.domain.vo.TagVO;
import com.stellarink.service.post.PostServiceImpl;
import com.stellarink.service.tag.TagService;
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
        List<PostEntity> posts = postMapper.selectList(new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getStatus, PostStatus.PUBLISHED.getValue())
                .select(PostEntity::getTags));
        Map<String, Long> counts = new LinkedHashMap<>();
        posts.stream()
                .map(PostEntity::getTags)
                .forEach(tags -> PostServiceImpl.splitTags(tags)
                        .forEach(tag -> counts.merge(tag, 1L, Long::sum)));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new TagVO(e.getKey(), e.getValue()))
                .toList();
    }
}
