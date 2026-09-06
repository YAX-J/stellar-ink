package com.stellarink.service.echo;

import com.stellarink.domain.dto.EchoCreateDTO;
import com.stellarink.domain.vo.EchoVO;

import java.util.List;

public interface EchoService {

    List<EchoVO> list();

    Long create(EchoCreateDTO dto);
}
