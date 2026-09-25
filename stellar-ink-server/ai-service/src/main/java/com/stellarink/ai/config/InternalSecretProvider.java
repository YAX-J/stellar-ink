package com.stellarink.ai.config;

import com.stellarink.aiclient.signature.InternalRequestSigner;
import org.springframework.stereotype.Component;

/**
 * 内部签名密钥的提供者：把「从环境变量读取」收在一个可替换的缝里。
 *
 * <p>与 {@link MasterKeyProvider} 同样的理由：{@code AI_INTERNAL_SECRET} 在 JVM 里不可写，
 * 若让调用点直接读环境变量，所有涉及签名的测试都只能靠反射改环境变量。
 * 生产仍是「只从环境变量读、缺失即拒绝签名」，这里只是多一个可注入的接缝。
 *
 * <p>**不缓存密钥**：测试可能需要换密钥，而读环境变量的开销可以忽略。
 */
@Component
public class InternalSecretProvider {

    /** 签名密钥是否已配置（探活/面板据此提示内部调用链是否可用）。 */
    public boolean configured() {
        return InternalRequestSigner.configured();
    }

    /**
     * 取签名器；未配置或长度不足时抛 {@link InternalRequestSigner.SecretMissingException}。
     *
     * <p>刻意**不给默认密钥**：一个弱默认值会让「内网就能冒充身份」这件事重新成立。
     */
    public InternalRequestSigner signer() {
        return InternalRequestSigner.fromEnvironment();
    }

    /** 用给定密钥取签名器（测试与将来接密钥管理服务用）。 */
    public InternalRequestSigner signer(String secret) {
        return InternalRequestSigner.fromSecret(secret);
    }

    /** 供日志展示的说明，不含密钥内容。 */
    public String describe() {
        return InternalRequestSigner.SECRET_ENV + (configured() ? "（已配置）" : "（未配置）");
    }
}
