package com.stellarink.echo.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.echo.mapper.EchoMapper;
import com.stellarink.echo.pojo.Echo;
import com.stellarink.sharedmodel.dto.echo.EchoCreateDTO;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.echo.EchoVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/echos")
@RequiredArgsConstructor
public class EchoController {

    private static final String ANONYMOUS = "匿名旅人";

    private final EchoMapper echoMapper;

    @GetMapping
    public Response<List<EchoVO>> list() {
        List<Echo> items = echoMapper.selectList(new LambdaQueryWrapper<Echo>()
                .orderByDesc(Echo::getId));
        return Response.success(items.stream().map(this::toVO).toList());
    }

    /** 投瓶入海（公开） */
    @PostMapping
    public Response<Void> create(@Valid @RequestBody EchoCreateDTO dto) {
        if (!StringUtils.hasText(dto.getContent())) {
            throw BusinessExceptionHelper.of("瓶子是空的，写句话再投进海里。");
        }
        Echo entity = new Echo();
        entity.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname().trim() : ANONYMOUS);
        entity.setContent(dto.getContent().trim());
        entity.setCreatedAt(LocalDateTime.now());
        echoMapper.insert(entity);
        log.info("投瓶入海 id={} 昵称={}", entity.getId(), entity.getNickname());
        return Response.success();
    }

    private EchoVO toVO(Echo entity) {
        EchoVO vo = new EchoVO();
        vo.setId(entity.getId());
        vo.setNickname(entity.getNickname());
        vo.setContent(entity.getContent());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
