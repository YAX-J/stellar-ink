package com.stellarink.service.search;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stellarink.common.exception.BusinessException;
import com.stellarink.domain.dto.PostQueryDTO;
import com.stellarink.domain.vo.PostVO;
import com.stellarink.service.post.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private final PostService postService;

    @Override
    public IPage<PostVO> search(String keyword, int page, int size) {
        if (!StringUtils.hasText(keyword)) {
            throw new BusinessException("告诉发射器一个关键词。");
        }
        PostQueryDTO query = new PostQueryDTO();
        query.setPage(page);
        query.setSize(size);
        query.setKeyword(keyword);
        return postService.page(query);
    }
}
