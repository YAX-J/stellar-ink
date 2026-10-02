package com.stellarink.ai.service;

import com.stellarink.sharedmodel.vo.ai.AiMemoryVO;

import java.util.List;

/**
 * 作者记忆的落库与状态流转（M9-2）。
 *
 * <p><b>为什么「候选 / 规则」在 Python、而这里只管库</b>：抽取与「该不该记」的判断属于 AI 能力
 * （换模型、改提示词、调阈值都要动那里），而记忆的持久化、状态流转、用户隔离属于数据面。
 * 这条边界与 E4 的 Wiki 完全一致。
 *
 * <p><b>所有方法都带 {@code userId} 且不接收它作为请求参数</b>：身份由 Java 从 Sa-Token 取，
 * 前端传不了别人的 id —— 于是「用户 A 的记忆不会被用户 B 看到」由**取数范围**保证，
 * 而不是靠某处的过滤条件（过滤条件漏一处就是数据泄露）。
 */
public interface AiMemoryService {

    /**
     * 列出某个用户自己的记忆。
     *
     * @param status 过滤状态（null/空 = 除已删除外全部）
     * @param type   过滤类型（null/空 = 不限）
     */
    List<AiMemoryVO> listMine(Long userId, String status, String type);

    /**
     * 改状态：{@code active}（启用）/ {@code disabled}（用户主动关掉）。
     *
     * <p>只允许在这两档之间切：{@code deleted} 要走 {@link #delete}，
     * 否则「删除」会变成一次普通的字段更新，清理动作（证据/画像）就不会发生。
     */
    AiMemoryVO setStatus(Long userId, Long memoryId, String status);

    /**
     * 删除一条记忆（软删 + 清证据 + 清派生画像）。
     *
     * @return 被删掉的记忆 id
     */
    Long delete(Long userId, Long memoryId);

    /**
     * 全部清除：该用户所有记忆、证据与派生画像。
     *
     * @return 清掉的记忆条数
     */
    int clearAll(Long userId);
}
