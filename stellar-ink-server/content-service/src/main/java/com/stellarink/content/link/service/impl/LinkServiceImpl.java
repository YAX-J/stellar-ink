package com.stellarink.content.link.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.content.link.mapper.LinkMapper;
import com.stellarink.content.link.pojo.Link;
import com.stellarink.content.link.service.LinkService;
import com.stellarink.sharedmodel.dto.link.LinkApplyDTO;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.vo.link.LinkVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LinkServiceImpl implements LinkService {

    static final int PENDING = 0;
    static final int APPROVED = 1;
    static final int REJECTED = 2;

    private final LinkMapper linkMapper;

    @Override
    public List<LinkVO> listApproved() {
        return listByStatus(APPROVED);
    }

    @Override
    public List<LinkVO> listPending() {
        AuthHelper.requireAtLeast(Role.ADMIN);
        return listByStatus(PENDING);
    }

    @Override
    public void apply(LinkApplyDTO dto) {
        if (!StringUtils.hasText(dto.getName())) {
            throw BusinessExceptionHelper.of("你的站点名是空的。");
        }
        if (!StringUtils.hasText(dto.getUrl())) {
            throw BusinessExceptionHelper.of("站点地址是空的，信号发不出去。");
        }

        Link entity = new Link();
        entity.setName(dto.getName().trim());
        entity.setUrl(dto.getUrl().trim());
        entity.setDescription(StringUtils.hasText(dto.getDescription())
                ? dto.getDescription().trim() : "新来的邻居，信号确认中…");
        entity.setStatus(PENDING);
        entity.setCreatedAt(LocalDateTime.now());
        linkMapper.insert(entity);
        log.info("友链申请 id={} name={} url={}", entity.getId(), entity.getName(), entity.getUrl());
    }

    @Override
    public void review(Long id, Integer status) {
        AuthHelper.requireAtLeast(Role.ADMIN);
        if (status == null || (status != APPROVED && status != REJECTED)) {
            throw BusinessExceptionHelper.of("审核状态只能是 1（通过）或 2（驳回）。");
        }

        Link entity = linkMapper.selectById(id);
        if (entity == null) {
            throw BusinessExceptionHelper.of(ErrorCode.NOT_FOUND, "这个邻居不存在");
        }
        if (!Integer.valueOf(PENDING).equals(entity.getStatus())) {
            throw BusinessExceptionHelper.of("这条友链申请已经审核过了。");
        }
        entity.setStatus(status);
        linkMapper.updateById(entity);
        log.info("友链 {} 审核结果为 {}", id, status == APPROVED ? "通过" : "驳回");
    }

    private List<LinkVO> listByStatus(int status) {
        return linkMapper.selectList(new LambdaQueryWrapper<Link>()
                        .eq(Link::getStatus, status)
                        .orderByAsc(Link::getId))
                .stream()
                .filter(link -> Integer.valueOf(status).equals(link.getStatus()))
                .map(this::toVO)
                .toList();
    }

    private LinkVO toVO(Link entity) {
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
