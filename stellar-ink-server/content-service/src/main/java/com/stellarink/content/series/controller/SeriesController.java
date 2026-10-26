package com.stellarink.content.series.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.content.series.service.SeriesService;
import com.stellarink.sharedmodel.dto.series.SeriesCreateDTO;
import com.stellarink.sharedmodel.dto.series.SeriesUpdateDTO;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.series.SeriesDetailVO;
import com.stellarink.sharedmodel.vo.series.SeriesVO;
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

import java.util.Map;

/**
 * 系列 / 专栏接口。
 *
 * <p>读接口公开（与文章/笔记的读一致）；写接口需 AUTHOR，归属判定在服务层
 * （只有系列创建者能改删，ADMIN 也不能改别人的系列）。
 */
@RestController
@RequestMapping("/series")
@RequiredArgsConstructor
public class SeriesController {

    private final SeriesService seriesService;

    @GetMapping
    public Response<IPage<SeriesVO>> page(@RequestParam(required = false, defaultValue = "1") Integer page,
                                          @RequestParam(required = false, defaultValue = "20") Integer size) {
        return Response.success(seriesService.page(page, size));
    }

    @GetMapping("/{id}")
    public Response<SeriesDetailVO> detail(@PathVariable Long id) {
        return Response.success(seriesService.detail(id));
    }

    @PostMapping
    public Response<Map<String, Long>> create(@Valid @RequestBody SeriesCreateDTO dto) {
        AuthHelper.requireAtLeast(Role.AUTHOR);
        return Response.success(Map.of("id", seriesService.create(dto)));
    }

    @PutMapping("/{id}")
    public Response<Void> update(@PathVariable Long id, @Valid @RequestBody SeriesUpdateDTO dto) {
        AuthHelper.requireAtLeast(Role.AUTHOR);
        seriesService.update(id, dto);
        return Response.success();
    }

    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        AuthHelper.requireAtLeast(Role.AUTHOR);
        seriesService.delete(id);
        return Response.success();
    }
}
