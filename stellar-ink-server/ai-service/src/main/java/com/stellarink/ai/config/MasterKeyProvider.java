package com.stellarink.ai.config;

import com.stellarink.common.crypto.AesGcmCipher;
import com.stellarink.common.crypto.MasterKey;
import org.springframework.stereotype.Component;

/**
 * 主密钥的提供者：把「从环境变量读取」这件事收在一个可替换的缝里。
 *
 * <p>为什么不让 service 直接调 {@code MasterKey.load()}：环境变量在 JVM 里不可写，
 * 那样写的话所有涉及加解密的单测都得靠反射改环境变量。这里暴露成 bean，
 * 测试注入固定密钥即可，生产仍是「只从环境变量读」。
 */
@Component
public class MasterKeyProvider {

    /** 主密钥是否已配置（面板据此提示「能否写入 Key」）。 */
    public boolean configured() {
        return MasterKey.configured();
    }

    /** 读取主密钥；未配置或格式不对时抛 {@link MasterKey.MasterKeyMissingException}。 */
    public byte[] get() {
        return MasterKey.load();
    }

    /** 由 base64 字符串解析（便于测试与将来从密钥管理服务注入）。 */
    public byte[] parse(String base64) {
        return MasterKey.load(base64);
    }

    /** 供日志/面板展示的格式说明，不含密钥内容。 */
    public String describe() {
        return AesGcmCipher.MASTER_KEY_ENV + (configured() ? "（已配置）" : "（未配置）");
    }
}
