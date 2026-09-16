package com.stellarink.content.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.content.post.pojo.PostView;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PostViewMapper extends BaseMapper<PostView> {

    /** 建立今天的计数闸门（首次插入；已有行时不覆盖） */
    @Insert("""
            INSERT IGNORE INTO post_view (user_id, viewed_at)
            VALUES (#{userId}, CURDATE())
            """)
    int insertToday(@Param("userId") Long userId);

    /** 仅当闸门日期早于今天时推进；并发请求中最多一个线程更新成功。 */
    @Update("""
            UPDATE post_view
            SET viewed_at = CURDATE()
            WHERE user_id = #{userId}
              AND (viewed_at IS NULL OR viewed_at < CURDATE())
            """)
    int advanceToToday(@Param("userId") Long userId);

    /**
     * 原子抢占今天的浏览计数资格：已有旧记录先条件更新，无记录再 INSERT IGNORE。
     * 两条语句都依赖行锁/主键唯一约束，因此多实例并发时也只有一个请求返回 true。
     */
    default boolean claimToday(Long userId) {
        return advanceToToday(userId) > 0 || insertToday(userId) > 0;
    }
}
