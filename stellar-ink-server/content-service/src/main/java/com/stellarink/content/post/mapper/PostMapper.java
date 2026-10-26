package com.stellarink.content.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stellarink.content.post.pojo.Post;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PostMapper extends BaseMapper<Post> {

    /**
     * 关键词搜索 + 相关度排序：标题命中 3 分 / 标签命中 2 分 / 正文命中 1 分，同分按 id 倒序。
     *
     * <p>为什么单独一条 SQL 而不是在 {@code page()} 上加 {@code ORDER BY}：
     * MyBatis-Plus 的 {@code last()} 是**裸 SQL、不走参数绑定**，把关键词拼进去就是注入面；
     * 这里用 {@code #{kw}} 绑定，关键词只会作为值传给驱动。
     *
     * <p>只服务 {@code /search}：该接口本来就只有 keyword 一个筛选，
     * 不必背上年份/标签/排序档位那些动态条件（那是 {@code page()} 的职责）。
     */
    @Select("""
            SELECT * FROM post
            WHERE status = 1
              AND (title LIKE CONCAT('%', #{kw}, '%')
                OR tags LIKE CONCAT('%', #{kw}, '%')
                OR content LIKE CONCAT('%', #{kw}, '%'))
            ORDER BY (CASE WHEN title LIKE CONCAT('%', #{kw}, '%') THEN 3 ELSE 0 END
                    + CASE WHEN tags LIKE CONCAT('%', #{kw}, '%') THEN 2 ELSE 0 END
                    + CASE WHEN content LIKE CONCAT('%', #{kw}, '%') THEN 1 ELSE 0 END) DESC,
                     id DESC
            """)
    IPage<Post> searchPage(Page<Post> page, @Param("kw") String keyword);
}
