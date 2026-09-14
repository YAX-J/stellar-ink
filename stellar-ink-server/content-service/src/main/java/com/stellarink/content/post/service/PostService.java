package com.stellarink.content.post.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stellarink.sharedmodel.dto.post.PostCreateDTO;
import com.stellarink.sharedmodel.dto.post.PostQueryDTO;
import com.stellarink.sharedmodel.dto.post.PostUpdateDTO;
import com.stellarink.sharedmodel.vo.post.GlowResultVO;
import com.stellarink.sharedmodel.vo.post.PostDetailVO;
import com.stellarink.sharedmodel.vo.post.PostVO;

public interface PostService {

    /** 分页查询（星图 / 星尘卡片 / 长卷共用） */
    IPage<PostVO> page(PostQueryDTO query);

    /** 当前作者自己的草稿列表 */
    IPage<PostVO> mine(PostQueryDTO query);

    /** 深读舱详情，附前后相邻星与当前用户的点赞态 */
    PostDetailVO detail(Long id);

    /** 执笔舱发射，返回新文章 id */
    Long create(PostCreateDTO dto);

    void update(Long id, PostUpdateDTO dto);

    void delete(Long id);

    /** 为这颗星补充光芒（登录用户一人一次），返回最新光芒数与点赞态 */
    GlowResultVO glow(Long id);

    /**
     * 记录一次浏览。登录用户按天去重（同一天多次刷新只计一次），未登录访客每次计数。
     * @return 是否真正计入
     */
    boolean recordView(Long id);
}
