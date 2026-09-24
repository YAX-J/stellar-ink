package com.stellarink.common.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密钥加密的契约测试，基准是 {@code stellar-ink-ai/tests/fixtures/key_vector.json}。
 *
 * <p>Python 侧读同一份向量做反向验证（同一条密文必须解出同一明文），因此任何一侧改了算法、
 * 密文格式或掩码规则，两侧单测会同时红 —— 这正是「Java 写、Python 读」这条链路最需要的保护。
 */
class CryptoTest {

    private static final Path FIXTURE = locateFixture();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 从当前目录向上找仓库根：Surefire 在模块目录跑，exec:java 在 reactor 根跑，两者都要能用。 */
    private static Path locateFixture() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("stellar-ink-ai/tests/fixtures/key_vector.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到密钥向量文件，起点：" + Path.of("").toAbsolutePath());
    }

    private static JsonNode vector() throws IOException {
        return MAPPER.readTree(FIXTURE.toFile());
    }

    @Test
    @DisplayName("向量文件可达，且密文已生成（还是占位符说明没跑过 bootstrapper）")
    void fixtureIsGenerated() throws IOException {
        assertTrue(Files.exists(FIXTURE), "找不到向量文件：" + FIXTURE.toAbsolutePath());
        String ciphertext = vector().get("ciphertext").asText();
        assertFalse(ciphertext.contains("PLACEHOLDER"),
                "向量里的密文还是占位符，请先跑 CryptoVectorBootstrapper 生成");
    }

    @Test
    @DisplayName("固定向量加密：结果与固化密文逐字节一致（nonce 固定）")
    void encryptionMatchesFrozenVector() throws IOException {
        JsonNode v = vector();

        String actual = AesGcmCipher.encrypt(
                v.get("plaintext").asText(),
                MasterKey.load(v.get("masterKeyB64").asText()),
                Base64.getDecoder().decode(v.get("nonceB64").asText()));

        assertEquals(v.get("ciphertext").asText(), actual,
                "密文与固化向量不一致：算法或格式变了，Python 侧会解不开");
    }

    @Test
    @DisplayName("固定向量解密：取回原明文")
    void decryptionRoundTrips() throws IOException {
        JsonNode v = vector();

        String plaintext = AesGcmCipher.decrypt(
                v.get("ciphertext").asText(),
                MasterKey.load(v.get("masterKeyB64").asText()));

        assertEquals(v.get("plaintext").asText(), plaintext);
    }

    @Test
    @DisplayName("篡改密文必须认证失败，不能返回半截明文")
    void tamperedCiphertextIsRejected() throws IOException {
        JsonNode v = vector();

        assertThrows(AesGcmCipher.DecryptException.class, () -> AesGcmCipher.decrypt(
                v.get("tamperedCiphertext").asText(),
                MasterKey.load(v.get("masterKeyB64").asText())));
    }

    @Test
    @DisplayName("换错密钥必须认证失败（而不是解出乱码）")
    void wrongKeyIsRejected() throws IOException {
        JsonNode v = vector();

        assertThrows(AesGcmCipher.DecryptException.class, () -> AesGcmCipher.decrypt(
                v.get("ciphertext").asText(),
                MasterKey.load(v.get("wrongKeyB64").asText())));
    }

    @Test
    @DisplayName("同一明文两次加密结果不同（nonce 必须每次随机）")
    void nonceIsRandomPerEncryption() throws IOException {
        byte[] key = MasterKey.load(vector().get("masterKeyB64").asText());
        String plaintext = "sk-live-0123456789abcdef";

        String first = AesGcmCipher.encrypt(plaintext, key);
        String second = AesGcmCipher.encrypt(plaintext, key);

        assertNotEquals(first, second, "两次加密用同一个 nonce 会直接毁掉 GCM 的安全性");
        assertEquals(plaintext, AesGcmCipher.decrypt(first, key));
        assertEquals(plaintext, AesGcmCipher.decrypt(second, key));
    }

    @Test
    @DisplayName("掩码规则固定：只用于回显，不泄露中段，也不会因短串崩掉")
    void maskHidesTheMiddle() throws IOException {
        JsonNode v = vector();
        String plaintext = v.get("plaintext").asText();

        assertEquals(v.get("masked").asText(), AesGcmCipher.mask(plaintext));
        assertFalse(AesGcmCipher.mask(plaintext).contains("stellar"), "掩码里不该出现明文主体");
        assertEquals("ab…", AesGcmCipher.mask("abcdef"));
        assertEquals("", AesGcmCipher.mask(""));
        assertEquals("", AesGcmCipher.mask(null));
    }

    @Test
    @DisplayName("格式非法与版本不支持都给出可修复的错，而不是静默失败")
    void malformedCiphertextIsReported() {
        byte[] key = MasterKey.load(Base64.getEncoder().encodeToString(new byte[32]));

        assertThrows(AesGcmCipher.CipherFormatException.class,
                () -> AesGcmCipher.decrypt("not-a-token", key));
        assertThrows(AesGcmCipher.CipherFormatException.class,
                () -> AesGcmCipher.decrypt("v2:AAAA:BBBB", key));
        assertThrows(AesGcmCipher.CipherFormatException.class,
                () -> AesGcmCipher.decrypt("v1:!!!:BBBB", key));
    }

    @Test
    @DisplayName("主密钥缺失/过短/非 base64 都归类为「配置问题」")
    void masterKeyProblemsAreActionable() {
        assertThrows(MasterKey.MasterKeyMissingException.class, () -> MasterKey.load(""));
        assertThrows(MasterKey.MasterKeyMissingException.class, () -> MasterKey.load("  "));
        assertThrows(MasterKey.MasterKeyMissingException.class, () -> MasterKey.load("not base64!!"));
        assertThrows(MasterKey.MasterKeyMissingException.class,
                () -> MasterKey.load(Base64.getEncoder().encodeToString(new byte[8])));
    }
}
