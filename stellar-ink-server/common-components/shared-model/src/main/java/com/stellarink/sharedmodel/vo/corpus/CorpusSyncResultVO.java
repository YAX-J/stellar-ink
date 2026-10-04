package com.stellarink.sharedmodel.vo.corpus;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次语料同步的结果（`POST /ai/admin/corpus/sync` 的返回）。
 *
 * <p>为什么要把这几个数字分开回：只回「同步了 N 条」无法回答
 * 「为什么问答里搜不到我刚发的文章」—— 需要能区分
 * 「上游没返回它」（`upstream` 里没有 = 上游认为它不该被索引）
 * 与「返回了但我没写进去」（`failed`）。
 */
@Data
public class CorpusSyncResultVO {

    /** 上游（content-service）这次给出的条目数。 */
    private int upstream;

    /** 新插入的行数。 */
    private int inserted;

    /** 内容哈希变了、被更新的行数。 */
    private int updated;

    /** 哈希没变、原样保留的行数。 */
    private int unchanged;

    /** 上游不再返回、被删除的行数（下架 / 已删 / **转为私有**都会走到这里）。 */
    private int removed;

    /** 同步后表里的总行数。 */
    private int total;

    /** 上游拉取是否失败（true 时**没有做任何删除**，表保持原样）。 */
    private boolean failed;

    /** 失败原因（给人看的可操作提示；成功时为 null）。 */
    private String reason;

    private LocalDateTime syncedAt;
}
