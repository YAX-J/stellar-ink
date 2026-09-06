package com.stellarink.service.search;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stellarink.domain.dto.PostQueryDTO;
import com.stellarink.domain.vo.PostVO;

public interface SearchService {

    /** 按标题/正文关键字搜索已发布文章 */
    IPage<PostVO> search(String keyword, int page, int size);
}
