package com.stellarink.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
}
