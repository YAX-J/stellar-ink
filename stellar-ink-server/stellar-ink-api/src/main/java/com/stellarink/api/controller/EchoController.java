package com.stellarink.api.controller;

import com.stellarink.common.result.Result;
import com.stellarink.domain.dto.EchoCreateDTO;
import com.stellarink.domain.vo.EchoVO;
import com.stellarink.service.echo.EchoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/echos")
@RequiredArgsConstructor
public class EchoController {

    private final EchoService echoService;

    @GetMapping
    public Result<List<EchoVO>> list() {
        return Result.ok(echoService.list());
    }

    /** 投瓶入海（公开） */
    @PostMapping
    public Result<Void> create(@RequestBody EchoCreateDTO dto) {
        echoService.create(dto);
        return Result.ok();
    }
}
