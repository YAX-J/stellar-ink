package com.stellarink.service.meteor;

import com.stellarink.domain.dto.MeteorCreateDTO;
import com.stellarink.domain.vo.MeteorVO;

import java.util.List;

public interface MeteorService {

    List<MeteorVO> list(Integer limit);

    Long create(MeteorCreateDTO dto);

    void delete(Long id);
}
