package com.stellarink.meteor.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.meteor.mapper.MeteorMapper;
import com.stellarink.meteor.pojo.Meteor;
import com.stellarink.sharedmodel.dto.meteor.MeteorCreateDTO;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.meteor.MeteorVO;
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
    public Response<List<MeteorVO>> list(@RequestParam(required = false, defaultValue = "50") Integer limit) {
        List<Meteor> items = meteorMapper.selectList(new LambdaQueryWrapper<Meteor>()
                .orderByDesc(Meteor::getId)
                .last(limit != null && limit > 0 ? "LIMIT " + limit : ""));
        return Response.success(items.stream().map(this::toVO).toList());
    }

    /** 发射流星（网关鉴权） */
    @PostMapping
    public Response<Void> create(@RequestBody MeteorCreateDTO dto) {
        if (!StringUtils.hasText(dto.getContent())) {
            throw BusinessExceptionHelper.of("此刻的念头是空的，写一句再发射。");
        }
        Meteor entity = new Meteor();
        entity.setContent(dto.getContent().trim());
        entity.setCreatedAt(LocalDateTime.now());
        meteorMapper.insert(entity);
        log.info("发射流星 id={}", entity.getId());
        return Response.success();
    }

    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        meteorMapper.deleteById(id);
        log.info("删除流星 id={}", id);
        return Response.success();
    }

    private MeteorVO toVO(Meteor entity) {
        MeteorVO vo = new MeteorVO();
        vo.setId(entity.getId());
        vo.setContent(entity.getContent());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
