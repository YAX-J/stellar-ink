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

    /** 本页条目（按 updatedAt 升序，id 升序；便于用 updatedAt 做游标续拉）。 */
    private List<CorpusItemVO> items;

    /** 是否还有没返回的条目（true 时用 {@link #maxUpdatedAt} 作为下次的 since）。 */
    private Boolean truncated;

    /** 本次实际生效的上限（回显，便于调用方判断是不是自己传的值被夹住了）。 */
    private Integer limit;

    /** 本页里最大的 updatedAt（游标）。没有条目时为 null。 */
    private LocalDateTime maxUpdatedAt;
}
