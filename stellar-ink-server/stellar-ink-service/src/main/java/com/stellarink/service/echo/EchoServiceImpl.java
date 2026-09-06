package com.stellarink.service.echo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.common.exception.BusinessException;
import com.stellarink.dao.entity.EchoEntity;
import com.stellarink.dao.mapper.EchoMapper;
import com.stellarink.domain.dto.EchoCreateDTO;
import com.stellarink.domain.vo.EchoVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EchoServiceImpl implements EchoService {

    private static final String ANONYMOUS = "匿名旅人";

    private final EchoMapper echoMapper;

    @Override
    public List<EchoVO> list() {
        return echoMapper.selectList(new LambdaQueryWrapper<EchoEntity>()
                .orderByDesc(EchoEntity::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    public Long create(EchoCreateDTO dto) {
        if (!StringUtils.hasText(dto.getContent())) {
            throw new BusinessException("瓶子是空的，写句话再投进海里。");
        }
        EchoEntity entity = new EchoEntity();
        entity.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname().trim() : ANONYMOUS);
        entity.setContent(dto.getContent().trim());
        entity.setCreatedAt(LocalDateTime.now());
        echoMapper.insert(entity);
        log.info("投瓶入海 id={} 昵称={}", entity.getId(), entity.getNickname());
        return entity.getId();
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
