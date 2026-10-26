package com.stellarink.content.note.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stellarink.content.note.pojo.Note;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NoteMapper extends BaseMapper<Note> {

    /**
     * 技术笔记的关键词搜索 + 相关度排序（标题 3 / 标签 2 / 正文 1）。
     *
     * <p>与 {@link com.stellarink.content.post.mapper.PostMapper#searchPage} 同一套口径，
     * 但必须自己带「已发布 + 公开」两个条件：私有笔记不得出现在任何搜索里。
     */
    @Select("""
            SELECT * FROM note
            WHERE status = 1
              AND visibility = 'PUBLIC'
              AND (title LIKE CONCAT('%', #{kw}, '%')
                OR tags LIKE CONCAT('%', #{kw}, '%')
                OR content LIKE CONCAT('%', #{kw}, '%'))
            ORDER BY (CASE WHEN title LIKE CONCAT('%', #{kw}, '%') THEN 3 ELSE 0 END
                    + CASE WHEN tags LIKE CONCAT('%', #{kw}, '%') THEN 2 ELSE 0 END
                    + CASE WHEN content LIKE CONCAT('%', #{kw}, '%') THEN 1 ELSE 0 END) DESC,
                     id DESC
            """)
    IPage<Note> searchPage(Page<Note> page, @Param("kw") String keyword);
}
