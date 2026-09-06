package com.stellarink.stats.controller;

import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.stats.StatsVO;
import com.stellarink.stats.service.StatsService;
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
    public Response<StatsVO> overview() {
        return Response.success(statsService.overview());
    }
}
