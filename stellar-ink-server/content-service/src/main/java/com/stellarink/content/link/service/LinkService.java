package com.stellarink.content.link.service;

import com.stellarink.sharedmodel.dto.link.LinkApplyDTO;
import com.stellarink.sharedmodel.vo.link.LinkVO;

import java.util.List;

public interface LinkService {

    /** 公开可见的已接入友链。 */
    List<LinkVO> listApproved();

    /** 站长可见的待审核申请。 */
    List<LinkVO> listPending();

    /** 提交友链申请。 */
    void apply(LinkApplyDTO dto);

    /** 审核友链：1 通过，2 驳回。 */
    void review(Long id, Integer status);
}
