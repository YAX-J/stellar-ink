package com.stellarink.link.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.common.exception.BusinessException;
import com.stellarink.common.result.Result;
import com.stellarink.common.result.ResultCode;
import com.stellarink.link.dto.LinkApplyDTO;
import com.stellarink.link.entity.LinkEntity;
import com.stellarink.link.mapper.LinkMapper;
import com.stellarink.link.vo.LinkVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/links")
@RequiredArgsConstructor
public class LinkController {

    private final LinkMapper linkMapper;

    @GetMapping
    public Result<List<LinkVO>> list() {
        return Result.ok(linkMapper.selectList(new LambdaQueryWrapper<LinkEntity>()
                .orderByAsc(LinkEntity::getId))
                .stream().map(this::toVO).toList());
    }

    /** 申请接入星链（公开） */
    @PostMapping
    public Result<Void> apply(@RequestBody LinkApplyDTO dto) {
        if (!StringUtils.hasText(dto.getName())) {
            throw new BusinessException("你的站点名是空的。");
        }
        if (!StringUtils.hasText(dto.getUrl())) {
            throw new BusinessException("站点地址是空的，信号发不出去。");
        }
        LinkEntity entity = new LinkEntity();
        entity.setName(dto.getName().trim());
        entity.setUrl(dto.getUrl().trim());
        entity.setDescription(StringUtils.hasText(dto.getDescription())
                ? dto.getDescription().trim() : "新来的邻居，信号确认中…");
        entity.setStatus(0);
        entity.setCreatedAt(LocalDateTime.now());
        linkMapper.insert(entity);
        log.info("友链申请 id={} name={} url={}", entity.getId(), entity.getName(), entity.getUrl());
        return Result.ok();
    }

    /** 站长确认 / 拒绝（网关鉴权）：status 1 接入 0 待确认 */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        LinkEntity entity = linkMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "这个邻居不存在");
        }
        entity.setStatus(status);
        linkMapper.updateById(entity);
        log.info("友链 {} 状态变更为 {}", id, status);
        return Result.ok();
    }

    private LinkVO toVO(LinkEntity entity) {
        LinkVO vo = new LinkVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setUrl(entity.getUrl());
        vo.setDescription(entity.getDescription());
        vo.setStatus(entity.getStatus());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
