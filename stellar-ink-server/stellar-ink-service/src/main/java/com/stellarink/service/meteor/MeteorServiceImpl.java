package com.stellarink.service.meteor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.common.exception.BusinessException;
import com.stellarink.common.result.ResultCode;
import com.stellarink.dao.entity.MeteorEntity;
import com.stellarink.dao.mapper.MeteorMapper;
import com.stellarink.domain.dto.MeteorCreateDTO;
import com.stellarink.domain.vo.MeteorVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeteorServiceImpl implements MeteorService {

    private final MeteorMapper meteorMapper;

    @Override
    public List<MeteorVO> list(Integer limit) {
        List<MeteorEntity> items = meteorMapper.selectList(new LambdaQueryWrapper<MeteorEntity>()
                .orderByDesc(MeteorEntity::getId)
                .last(limit != null && limit > 0 ? "LIMIT " + limit : ""));
        return items.stream().map(this::toVO).toList();
    }

    @Override
    public Long create(MeteorCreateDTO dto) {
        if (!StringUtils.hasText(dto.getContent())) {
            throw new BusinessException("此刻的念头是空的，写一句再发射。");
        }
        MeteorEntity entity = new MeteorEntity();
        entity.setContent(dto.getContent().trim());
        entity.setCreatedAt(LocalDateTime.now());
        meteorMapper.insert(entity);
        log.info("发射流星 id={}", entity.getId());
        return entity.getId();
    }

    @Override
    public void delete(Long id) {
        meteorMapper.deleteById(id);
        log.info("删除流星 id={}", id);
    }

    private MeteorVO toVO(MeteorEntity entity) {
        MeteorVO vo = new MeteorVO();
        vo.setId(entity.getId());
        vo.setContent(entity.getContent());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
