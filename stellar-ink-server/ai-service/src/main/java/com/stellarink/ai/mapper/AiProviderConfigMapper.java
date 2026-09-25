package com.stellarink.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellarink.ai.pojo.AiProviderConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型配置的持久化入口。
 *
 * <p>注意：这里**不需要**任何自定义 SQL —— 密钥的加解密放在 service 层，
 * mapper 只负责存取密文字节，避免以后有人在 SQL 里拼明文。
 */
@Mapper
public interface AiProviderConfigMapper extends BaseMapper<AiProviderConfig> {

    /**
     * 更新一行，并**显式**写入 {@code model_id}（允许写 NULL）。
     *
     * <p>为什么不能用 {@code updateById}：MyBatis-Plus 默认的字段策略是 NOT_NULL，
     * 也就是**忽略实体里的 null 字段**。而这里的 null 是有意义的两个业务动作：
     * 「面板手填配置」（不再绑定库里某一条）与「强制删除模型时解绑」。
     * 用 {@code updateById} 的表现是「接口成功、刷新又回来了」—— 踩过一次，见 AGENTS.md。
     *
     * <p>只在值为 null 时才追加 set 子句：非 null 时实体自己就会写这一列，
     * 两边都写会生成 {@code SET model_id=?, ..., model_id=?}（MySQL 容忍、H2 直接报语法错）。
     *
     * <p>放在 mapper 的 default 方法里，是为了让两处调用方（角色配置服务、模型库服务）
     * 共用同一份口径，而不是各写一遍 updateWrapper 再各漏一处。
     */
    default int updateWithModelId(AiProviderConfig row) {
        var wrapper = Wrappers.<AiProviderConfig>lambdaUpdate()
                .eq(AiProviderConfig::getId, row.getId());
        if (row.getModelId() == null) {
            wrapper.set(AiProviderConfig::getModelId, null);
        }
        return update(row, wrapper);
    }
}
