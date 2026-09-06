package com.stellarink.service.link;

import com.stellarink.domain.dto.LinkApplyDTO;
import com.stellarink.domain.vo.LinkVO;

import java.util.List;

public interface LinkService {

    List<LinkVO> list();

    /** 申请接入星链，默认待确认 */
    Long apply(LinkApplyDTO dto);

    /** 站长确认 / 拒绝 */
    void updateStatus(Long id, int status);
}
