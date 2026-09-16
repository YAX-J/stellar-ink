package com.stellarink.content.note.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stellarink.sharedmodel.dto.note.NoteCreateDTO;
import com.stellarink.sharedmodel.dto.note.NoteQueryDTO;
import com.stellarink.sharedmodel.dto.note.NoteUpdateDTO;
import com.stellarink.sharedmodel.vo.note.NoteDetailVO;
import com.stellarink.sharedmodel.vo.note.NoteVO;

public interface NoteService {

    /** 公开笔记列表：强制「已发布 + 公开」，不接受调用方指定可见性 */
    IPage<NoteVO> page(NoteQueryDTO query);

    /** 我的笔记（含私有与草稿），只返回当前登录用户自己的 */
    IPage<NoteVO> mine(NoteQueryDTO query);

    /** 我的已发布笔记复核队列，按 180 天有效期筛选 */
    IPage<NoteVO> review(NoteQueryDTO query);

    /** 详情：私有笔记仅作者本人可读，其他人一律 404 */
    NoteDetailVO detail(Long id);

    /** 新建，返回新笔记 id */
    Long create(NoteCreateDTO dto);

    void update(Long id, NoteUpdateDTO dto);

    void delete(Long id);

    /** 标记「结论仍然有效」，返回新的验证时间 */
    java.time.LocalDateTime verify(Long id);

    /**
     * 记录一次浏览：仅公开且已发布的笔记计数，作者本人与草稿不计。
     * 登录用户按天去重（复用全局浏览闸门），未登录访客每次计数。
     */
    boolean recordView(Long id);
}
