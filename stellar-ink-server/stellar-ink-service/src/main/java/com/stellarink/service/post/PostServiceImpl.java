package com.stellarink.service.post;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stellarink.common.exception.BusinessException;
import com.stellarink.common.result.ResultCode;
import com.stellarink.dao.entity.PostEntity;
import com.stellarink.dao.mapper.PostMapper;
import com.stellarink.domain.dto.PostCreateDTO;
import com.stellarink.domain.dto.PostQueryDTO;
import com.stellarink.domain.dto.PostUpdateDTO;
import com.stellarink.domain.enums.PostStatus;
import com.stellarink.domain.vo.PostDetailVO;
import com.stellarink.domain.vo.PostVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int SUMMARY_LEN = 60;

    private final PostMapper postMapper;

    @Override
    public IPage<PostVO> page(PostQueryDTO query) {
        LambdaQueryWrapper<PostEntity> wrapper = new LambdaQueryWrapper<PostEntity>()
                .eq(query.isPublishedOnly(), PostEntity::getStatus, PostStatus.PUBLISHED.getValue())
                .eq(query.getStatus() != null, PostEntity::getStatus, query.getStatus())
                .apply(query.getYear() != null, "YEAR(created_at) = {0}", query.getYear())
                .like(StringUtils.hasText(query.getTag()), PostEntity::getTags, query.getTag())
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(PostEntity::getTitle, query.getKeyword())
                        .or()
                        .like(PostEntity::getContent, query.getKeyword()))
                .orderByDesc(PostEntity::getId);

        IPage<PostEntity> page = postMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);
        return page.convert(this::toVO);
    }

    @Override
    public PostDetailVO detail(Long id) {
        PostEntity post = postMapper.selectById(id);
        if (post == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "这颗星不存在");
        }

        PostDetailVO vo = new PostDetailVO();
        vo.setId(post.getId());
        vo.setTitle(post.getTitle());
        vo.setContent(post.getContent());
        vo.setTags(splitTags(post.getTags()));
        vo.setWordCount(post.getWordCount());
        vo.setReadMinutes((int) Math.ceil(post.getWordCount() / 400.0));
        vo.setYear(post.getCreatedAt().getYear());
        vo.setDate(post.getCreatedAt().toLocalDate().format(DATE_FMT));
        vo.setGlow(post.getGlow());
        vo.setPrev(neighbor(post.getId(), true));
        vo.setNext(neighbor(post.getId(), false));
        return vo;
    }

    @Override
    public Long create(PostCreateDTO dto) {
        String content = requireContent(dto.getContent());
        LocalDateTime now = LocalDateTime.now();
        PostEntity entity = new PostEntity();
        entity.setTitle(StringUtils.hasText(dto.getTitle()) ? dto.getTitle().trim() : "无题的一夜");
        entity.setContent(content);
        entity.setTags(joinTags(dto.getTags()));
        entity.setWordCount(countWords(content));
        entity.setStatus(dto.getStatus() != null ? dto.getStatus() : PostStatus.PUBLISHED.getValue());
        entity.setGlow(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        postMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public void update(Long id, PostUpdateDTO dto) {
        PostEntity post = requirePost(id);
        if (StringUtils.hasText(dto.getTitle())) {
            post.setTitle(dto.getTitle().trim());
        }
        if (dto.getContent() != null) {
            String content = requireContent(dto.getContent());
            post.setContent(content);
            post.setWordCount(countWords(content));
        }
        if (dto.getTags() != null) {
            post.setTags(joinTags(dto.getTags()));
        }
        if (dto.getStatus() != null) {
            post.setStatus(dto.getStatus());
        }
        post.setUpdatedAt(LocalDateTime.now());
        postMapper.updateById(post);
    }

    @Override
    public void delete(Long id) {
        requirePost(id);
        postMapper.deleteById(id);
    }

    @Override
    public Integer glow(Long id) {
        requirePost(id);
        postMapper.update(null, new LambdaUpdateWrapper<PostEntity>()
                .eq(PostEntity::getId, id)
                .setSql("glow = glow + 1"));
        return postMapper.selectById(id).getGlow();
    }

    private PostEntity requirePost(Long id) {
        PostEntity post = postMapper.selectById(id);
        if (post == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "这颗星不存在");
        }
        return post;
    }

    /** 相邻星：prev 取更新的一颗，next 取更早的一颗 */
    private PostDetailVO.NeighborVO neighbor(Long id, boolean newer) {
        LambdaQueryWrapper<PostEntity> wrapper = new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getStatus, PostStatus.PUBLISHED.getValue())
                .gt(newer, PostEntity::getId, id)
                .lt(!newer, PostEntity::getId, id)
                .orderBy(newer, true, PostEntity::getId)
                .orderBy(!newer, false, PostEntity::getId)
                .last("LIMIT 1");
        PostEntity neighbor = postMapper.selectOne(wrapper);
        if (neighbor == null) {
            return null;
        }
        PostDetailVO.NeighborVO vo = new PostDetailVO.NeighborVO();
        vo.setId(neighbor.getId());
        vo.setTitle(neighbor.getTitle());
        return vo;
    }

    private PostVO toVO(PostEntity post) {
        PostVO vo = new PostVO();
        vo.setId(post.getId());
        vo.setTitle(post.getTitle());
        vo.setTags(splitTags(post.getTags()));
        vo.setWordCount(post.getWordCount());
        vo.setYear(post.getCreatedAt().getYear());
        vo.setDate(post.getCreatedAt().toLocalDate().format(DATE_FMT));
        vo.setGlow(post.getGlow());
        vo.setStatus(post.getStatus());
        String content = post.getContent() == null ? "" : post.getContent();
        vo.setSummary(content.length() > SUMMARY_LEN ? content.substring(0, SUMMARY_LEN) + "……" : content);
        return vo;
    }

    public static List<String> splitTags(String tags) {
        if (!StringUtils.hasText(tags)) {
            return List.of();
        }
        return Arrays.stream(tags.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    static String joinTags(List<String> tags) {
        if (tags == null) {
            return "";
        }
        return String.join(",", tags.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    static int countWords(String content) {
        return content.replaceAll("\\s", "").length();
    }

    static String requireContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException("正文不能为空，先写点什么再发射。");
        }
        return content.trim();
    }
}
