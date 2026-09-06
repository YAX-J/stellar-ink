package com.stellarink.echo.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.common.exception.BusinessException;
import com.stellarink.common.result.Result;
import com.stellarink.echo.dto.EchoCreateDTO;
import com.stellarink.echo.entity.EchoEntity;
import com.stellarink.echo.mapper.EchoMapper;
import com.stellarink.echo.vo.EchoVO;
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
    public Result<List<EchoVO>> list() {
        List<EchoEntity> items = echoMapper.selectList(new LambdaQueryWrapper<EchoEntity>()
                .orderByDesc(EchoEntity::getId));
        return Result.ok(items.stream().map(this::toVO).toList());
    }

    /** 投瓶入海（公开） */
    @PostMapping
    public Result<Void> create(@RequestBody EchoCreateDTO dto) {
        if (!StringUtils.hasText(dto.getContent())) {
            throw new BusinessException("瓶子是空的，写句话再投进海里。");
        }
        EchoEntity entity = new EchoEntity();
        entity.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname().trim() : ANONYMOUS);
        entity.setContent(dto.getContent().trim());
        entity.setCreatedAt(LocalDateTime.now());
        echoMapper.insert(entity);
        log.info("投瓶入海 id={} 昵称={}", entity.getId(), entity.getNickname());
        return Result.ok();
    }

    private EchoVO toVO(EchoEntity entity) {
        EchoVO vo = new EchoVO();
        vo.setId(entity.getId());
        vo.setNickname(entity.getNickname());
        vo.setContent(entity.getContent());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
