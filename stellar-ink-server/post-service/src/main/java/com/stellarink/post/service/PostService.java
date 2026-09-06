package com.stellarink.post.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stellarink.sharedmodel.dto.post.PostCreateDTO;
import com.stellarink.sharedmodel.dto.post.PostQueryDTO;
import com.stellarink.sharedmodel.dto.post.PostUpdateDTO;
import com.stellarink.sharedmodel.vo.post.PostDetailVO;
import com.stellarink.sharedmodel.vo.post.PostVO;

public interface PostService {

    /** 分页查询（星图 / 星尘卡片 / 长卷共用） */
    IPage<PostVO> page(PostQueryDTO query);

    /** 深读舱详情，附前后相邻星 */
    PostDetailVO detail(Long id);

    /** 执笔舱发射，返回新文章 id */
    Long create(PostCreateDTO dto);

    void update(Long id, PostUpdateDTO dto);

    void delete(Long id);

    /** 为这颗星补充光芒，返回新的光芒数 */
    Integer glow(Long id);
}
