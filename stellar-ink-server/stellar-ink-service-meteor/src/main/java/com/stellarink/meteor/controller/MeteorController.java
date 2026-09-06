package com.stellarink.meteor.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.common.exception.BusinessException;
import com.stellarink.common.result.Result;
import com.stellarink.meteor.dto.MeteorCreateDTO;
import com.stellarink.meteor.entity.MeteorEntity;
import com.stellarink.meteor.mapper.MeteorMapper;
import com.stellarink.meteor.vo.MeteorVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/meteors")
@RequiredArgsConstructor
public class MeteorController {

    private final MeteorMapper meteorMapper;

    @GetMapping
    public Result<List<MeteorVO>> list(@RequestParam(required = false, defaultValue = "50") Integer limit) {
        List<MeteorEntity> items = meteorMapper.selectList(new LambdaQueryWrapper<MeteorEntity>()
                .orderByDesc(MeteorEntity::getId)
                .last(limit != null && limit > 0 ? "LIMIT " + limit : ""));
        return Result.ok(items.stream().map(this::toVO).toList());
    }

    /** 发射流星（网关鉴权） */
    @PostMapping
    public Result<Void> create(@RequestBody MeteorCreateDTO dto) {
        if (!StringUtils.hasText(dto.getContent())) {
            throw new BusinessException("此刻的念头是空的，写一句再发射。");
        }
        MeteorEntity entity = new MeteorEntity();
        entity.setContent(dto.getContent().trim());
        entity.setCreatedAt(LocalDateTime.now());
        meteorMapper.insert(entity);
        log.info("发射流星 id={}", entity.getId());
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        meteorMapper.deleteById(id);
        log.info("删除流星 id={}", id);
        return Result.ok();
    }

    private MeteorVO toVO(MeteorEntity entity) {
        MeteorVO vo = new MeteorVO();
        vo.setId(entity.getId());
        vo.setContent(entity.getContent());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
