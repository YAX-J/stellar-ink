package com.stellarink.api.controller;

import com.stellarink.common.result.Result;
import com.stellarink.domain.dto.LinkApplyDTO;
import com.stellarink.domain.vo.LinkVO;
import com.stellarink.service.link.LinkService;
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
    public Result<List<LinkVO>> list() {
        return Result.ok(linkService.list());
    }

    /** 申请接入星链（公开） */
    @PostMapping
    public Result<Void> apply(@RequestBody LinkApplyDTO dto) {
        linkService.apply(dto);
        return Result.ok();
    }

    /** 站长确认 / 拒绝（需登录）：status 1 接入 0 待确认 */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        linkService.updateStatus(id, status);
        return Result.ok();
    }
}
