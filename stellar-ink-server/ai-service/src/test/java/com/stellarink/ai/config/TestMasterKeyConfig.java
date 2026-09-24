package com.stellarink.ai.config;

import com.stellarink.common.crypto.MasterKey;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 测试用的主密钥提供者：给一个固定的 32 字节密钥。
 *
 * <p>为什么需要它：生产代码刻意只从环境变量读主密钥（这是安全设计），
 * 而 JVM 的环境变量在测试里改不了 —— 若不为测试留这个缝，就只能靠反射改环境变量。
 * 这里替换的是「密钥来源」，加解密算法与密文格式仍是生产那套。
 */
@TestConfiguration
public class TestMasterKeyConfig {

    /** 与 key_vector.json 里的主密钥一致，便于跨语言对照 */
    public static final String MASTER_KEY_BASE64 = "c3RlbGxhci1pbmstYWktbWFzdGVyLWtleS0wMDAwMDA=";

    @Bean
    @Primary
    MasterKeyProvider testMasterKeyProvider() {
        return new MasterKeyProvider() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public byte[] get() {
                return MasterKey.load(MASTER_KEY_BASE64);
            }

            @Override
            public String describe() {
                return "test-master-key（仅测试）";
            }
        };
    }

    static {
        // 保证 base64 常量本身合法（写成错字时立刻失败，而不是等到加密那一步）
        if (Base64.getDecoder().decode(MASTER_KEY_BASE64).length != 32) {
            throw new IllegalStateException("测试主密钥必须是 32 字节");
        }
        if (MASTER_KEY_BASE64.getBytes(StandardCharsets.US_ASCII).length == 0) {
            throw new IllegalStateException("unreachable");
        }
    }
}
