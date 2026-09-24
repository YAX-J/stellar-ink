package com.stellarink.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 服务配置（前缀 {@code stellar.ink.ai}）。
 *
 * <p>**刻意不定义密钥字段**：{@code AI_INTERNAL_SECRET} 与模型 Key 在 M1/M2 引入时，
 * 必须走「缺失即拒绝启动」的独立读取路径，绝不作为可配置项出现（红线 §7.1）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "stellar.ink.ai")
public class AiProperties {

    /**
     * Python 服务基址（仅内网）。
     * <p>不注册 Nacos：Python 不参与 Java 服务发现，地址由部署配置固定。
     */
    private String pythonBaseUrl = "http://127.0.0.1:8200";
}
