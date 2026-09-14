package com.stellarink.content.note.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.content.note.pojo.Note;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NoteMapper extends BaseMapper<Note> {
}
