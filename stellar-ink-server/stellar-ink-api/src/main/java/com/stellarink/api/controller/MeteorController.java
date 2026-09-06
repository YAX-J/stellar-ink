package com.stellarink.api.controller;

import com.stellarink.common.result.Result;
import com.stellarink.domain.dto.MeteorCreateDTO;
import com.stellarink.domain.vo.MeteorVO;
import com.stellarink.service.meteor.MeteorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/meteors")
@RequiredArgsConstructor
public class MeteorController {

    private final MeteorService meteorService;

    @GetMapping
    public Result<List<MeteorVO>> list(@RequestParam(required = false, defaultValue = "50") Integer limit) {
        return Result.ok(meteorService.list(limit));
    }

    /** 发射流星（需登录） */
    @PostMapping
    public Result<Void> create(@RequestBody MeteorCreateDTO dto) {
        meteorService.create(dto);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        meteorService.delete(id);
        return Result.ok();
    }
}
