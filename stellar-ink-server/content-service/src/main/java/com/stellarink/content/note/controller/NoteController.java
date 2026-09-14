package com.stellarink.content.note.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.content.note.service.NoteService;
import com.stellarink.sharedmodel.dto.note.NoteCreateDTO;
import com.stellarink.sharedmodel.dto.note.NoteQueryDTO;
import com.stellarink.sharedmodel.dto.note.NoteUpdateDTO;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.note.NoteDetailVO;
import com.stellarink.sharedmodel.vo.note.NoteVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 技术笔记接口。
 *
 * <p>可见性规则：公开列表与详情对未登录开放（网关对 GET 全放行），
 * 「私有笔记仅作者可见」由服务层的 {@code ensureReadable} 兜住 —— 网关无法也不应承担这条过滤。
 * 写入类接口需 AUTHOR，归属判定只认作者本人（ADMIN 也不能改他人笔记）。
 */
@RestController
@RequestMapping("/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;

    /** 公开笔记列表：只返回「已发布 + 公开」，不接受可见性参数 */
    @GetMapping
    public Response<IPage<NoteVO>> page(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String tag,
                                        @RequestParam(required = false) String noteType,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String orderBy) {
        NoteQueryDTO query = new NoteQueryDTO();
        if (page != null) query.setPage(page);
        if (size != null) query.setSize(size);
        query.setTag(tag);
        query.setNoteType(noteType);
        query.setKeyword(keyword);
        query.setOrderBy(orderBy);
        /* 防御性固定：即使有人直接绕过网关调用本服务，也拿不到私有笔记 */
        query.setPublicOnly(true);
        return Response.success(noteService.page(query));
    }

    /** 我的笔记（含私有与草稿） */
    @GetMapping("/mine")
    public Response<IPage<NoteVO>> mine(@RequestParam(required = false, defaultValue = "1") Integer page,
                                        @RequestParam(required = false, defaultValue = "50") Integer size,
                                        @RequestParam(required = false) Integer status,
                                        @RequestParam(required = false) String visibility,
                                        @RequestParam(required = false) String noteType,
                                        @RequestParam(required = false) String keyword) {
        AuthHelper.requireAtLeast(Role.AUTHOR);
        NoteQueryDTO query = new NoteQueryDTO();
        query.setPage(page);
        query.setSize(size);
        query.setStatus(status);
        query.setVisibility(visibility);
        query.setNoteType(noteType);
        query.setKeyword(keyword);
        query.setPublicOnly(false);
        return Response.success(noteService.mine(query));
    }

    /** 详情：私有笔记仅作者本人可读，其他人 404 */
    @GetMapping("/{id}")
    public Response<NoteDetailVO> detail(@PathVariable Long id) {
        return Response.success(noteService.detail(id));
    }

    /** 新建笔记（默认私有草稿） */
    @PostMapping
    public Response<Map<String, Long>> create(@Valid @RequestBody NoteCreateDTO dto) {
        AuthHelper.requireAtLeast(Role.AUTHOR);
        return Response.success(Map.of("id", noteService.create(dto)));
    }

    @PutMapping("/{id}")
    public Response<Void> update(@PathVariable Long id, @Valid @RequestBody NoteUpdateDTO dto) {
        AuthHelper.requireAtLeast(Role.AUTHOR);
        noteService.update(id, dto);
        return Response.success();
    }

    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        AuthHelper.requireAtLeast(Role.AUTHOR);
        noteService.delete(id);
        return Response.success();
    }

    /** 标记「结论仍然有效」：解决笔记会过期的问题 */
    @PutMapping("/{id}/verify")
    public Response<Map<String, LocalDateTime>> verify(@PathVariable Long id) {
        AuthHelper.requireAtLeast(Role.AUTHOR);
        return Response.success(Map.of("verifiedAt", noteService.verify(id)));
    }

    /** 记录一次浏览（公开）：仅公开且已发布的笔记计数，作者本人不计 */
    @PostMapping("/{id}/viewed")
    public Response<Map<String, Boolean>> viewed(@PathVariable Long id) {
        return Response.success(Map.of("counted", noteService.recordView(id)));
    }
}
