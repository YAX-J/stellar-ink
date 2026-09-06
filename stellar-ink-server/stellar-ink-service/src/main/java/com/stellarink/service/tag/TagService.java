package com.stellarink.service.tag;

import com.stellarink.domain.vo.TagVO;

import java.util.List;

public interface TagService {

    /** 光谱：标签及文章计数，按计数降序 */
    List<TagVO> listWithCount();
}
