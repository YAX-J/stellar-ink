package com.stellarink.stats.controller;

import com.stellarink.common.result.Result;
import com.stellarink.stats.service.StatsService;
import com.stellarink.stats.vo.StatsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    /** 写作脉搏 */
    @GetMapping("/overview")
    public Result<StatsVO> overview() {
        return Result.ok(statsService.overview());
    }
}
