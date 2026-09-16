package com.stellarink.content.link.controller;

import com.stellarink.content.link.service.LinkService;
import com.stellarink.sharedmodel.dto.link.LinkApplyDTO;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.link.LinkVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/links")
@RequiredArgsConstructor
public class LinkController {

    private final LinkService linkService;

    @GetMapping
    public Response<List<LinkVO>> list() {
        return Response.success(linkService.listApproved());
    }

    /** 待审核申请（仅站长） */
    @GetMapping("/pending")
    public Response<List<LinkVO>> pending() {
        return Response.success(linkService.listPending());
    }

    /** 申请接入星链（公开） */
    @PostMapping
    public Response<Void> apply(@Valid @RequestBody LinkApplyDTO dto) {
        linkService.apply(dto);
        return Response.success();
    }

    /** 站长审核（网关与服务双重鉴权）：status 1 通过 / 2 驳回 */
    @PutMapping("/{id}/status")
    public Response<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        linkService.review(id, status);
        return Response.success();
    }
}
