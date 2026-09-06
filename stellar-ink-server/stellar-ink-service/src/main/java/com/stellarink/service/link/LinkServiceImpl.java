package com.stellarink.service.link;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.common.exception.BusinessException;
import com.stellarink.common.result.ResultCode;
import com.stellarink.dao.entity.LinkEntity;
import com.stellarink.dao.mapper.LinkMapper;
import com.stellarink.domain.dto.LinkApplyDTO;
import com.stellarink.domain.enums.LinkStatus;
import com.stellarink.domain.vo.LinkVO;
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

    private final LinkMapper linkMapper;

    @Override
    public List<LinkVO> list() {
        return linkMapper.selectList(new LambdaQueryWrapper<LinkEntity>()
                .orderByAsc(LinkEntity::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    public Long apply(LinkApplyDTO dto) {
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
        entity.setStatus(LinkStatus.PENDING.getValue());
        entity.setCreatedAt(LocalDateTime.now());
        linkMapper.insert(entity);
        log.info("友链申请 id={} name={} url={}", entity.getId(), entity.getName(), entity.getUrl());
        return entity.getId();
    }

    @Override
    public void updateStatus(Long id, int status) {
        LinkEntity entity = linkMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "这个邻居不存在");
        }
        entity.setStatus(status);
        linkMapper.updateById(entity);
        log.info("友链 {} 状态变更为 {}", id, status);
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
