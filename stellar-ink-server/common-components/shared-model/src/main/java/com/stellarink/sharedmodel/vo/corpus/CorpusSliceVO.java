package com.stellarink.sharedmodel.vo.corpus;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 语料清单的一页。
 *
 * <p>为什么要显式给出 {@code truncated} 而不是「默认无上限」：调用方（对账任务）必须能区分
 * 「就这么多」与「我没给全」。少了这个标记，截断会表现成「这些文章被删了」——
 * 对账于是去删索引，把好好的知识库删掉一半，而且看起来完全正常。
 * （同样的口径见检索审计与知识库盘点：宁可不全，不可假装全。）
 */
@Data
public class CorpusSliceVO {

    /** 本页条目按 kind 的字面量、id 升序；修改时间不参与翻页，避免同秒更新漏项。 */
    private List<CorpusItemVO> items;

    /** 是否还有条目；为 true 时必须携带 nextKind / nextId 续拉。 */
    private Boolean truncated;

    /** 本次实际生效的上限（回显，便于调用方判断是不是自己传的值被夹住了）。 */
    private Integer limit;

    /** 本页最大的修改时间，仅作信息展示，不再作为翻页游标。 */
    private LocalDateTime maxUpdatedAt;

    /** 本页最后一条的内容类型；与 nextId 组成稳定游标。 */
    private String nextKind;

    /** 本页最后一条的 ID；文章与笔记即使同号，也不会相互跳过。 */
    private Long nextId;
}
