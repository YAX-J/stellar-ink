package com.stellarink.content.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.content.post.pojo.PostView;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

@Mapper
public interface PostViewMapper extends BaseMapper<PostView> {

    /** 闸门里记的最近计数日期，没有记录时返回 null */
    @Select("SELECT viewed_at FROM post_view WHERE user_id = #{userId}")
    LocalDate findViewedAt(@Param("userId") Long userId);

    /** 建立今天的计数闸门（首次插入；已有行时不覆盖） */
    @Insert("""
            INSERT IGNORE INTO post_view (user_id, viewed_at)
            VALUES (#{userId}, CURDATE())
            """)
    int insertToday(@Param("userId") Long userId);

    /** 把闸门推进到今天（跨天时补上） */
    @Update("""
            UPDATE post_view
            SET viewed_at = CURDATE()
            WHERE user_id = #{userId}
            """)
    int touchToday(@Param("userId") Long userId);
}
