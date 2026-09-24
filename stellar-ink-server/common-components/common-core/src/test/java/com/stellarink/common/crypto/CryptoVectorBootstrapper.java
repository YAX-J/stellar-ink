package com.stellarink.common.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 鐢熸垚锛堣€屼笉鏄墜鍐欙級璺ㄨ瑷€娴嬭瘯鍚戦噺閲岀殑瀵嗘枃銆? *
 * <p>涓轰粈涔堢敤绋嬪簭鐢熸垚锛氬瘑鏂囨病娉曟墜绠楋紝鑰屼袱杈广€屽悇鑷姞瀵嗗啀鍚勮嚜瑙ｅ瘑銆嶆案杩滃彂鐜颁笉浜嗘牸寮忎笉涓€鑷?鈥斺€? * 蹇呴』鏈変竴浠?*鍥哄寲涓嬫潵鐨勫瘑鏂?*锛孞ava 璇诲畠銆丳ython 涔熻瀹冦€? *
 * <p>鐢ㄦ硶锛堝湪 stellar-ink-server 鐩綍涓嬶級锛? * <pre>
 * mvn -q -pl common-components/common-core test-compile `
 *   exec:java -Dexec.classpathScope=test `
 *   -Dexec.mainClass=com.stellarink.common.crypto.CryptoVectorBootstrapper
 * </pre>
 * 鐢熸垚鍚?*蹇呴』鎶?JSON 涓€璧锋彁浜?*锛涙敼浜嗘牸寮忥紙鐗堟湰鍙?nonce 闀垮害/tag 浣嶇疆锛夊氨瑕侀噸鏂扮敓鎴愩€? */
public final class CryptoVectorBootstrapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CryptoVectorBootstrapper() {
    }

    public static void main(String[] args) throws Exception {
        Path fixture = Path.of("..", "..", "..", "..", "stellar-ink-ai", "tests", "fixtures", "crypto_vector.json");
        if (!Files.exists(fixture)) {
            throw new IllegalStateException("鎵句笉鍒板悜閲忔枃浠讹細" + fixture.toAbsolutePath());
        }

        ObjectNode root = (ObjectNode) MAPPER.readTree(fixture.toFile());
        byte[] key = MasterKey.load(root.get("masterKeyB64").asText());
        byte[] nonce = Base64.getDecoder().decode(root.get("nonceB64").asText());
        String plaintext = root.get("plaintext").asText();

        String ciphertext = AesGcmCipher.encrypt(plaintext, key, nonce);
        root.put("ciphertext", ciphertext);
        root.put("tamperedCiphertext", tamper(ciphertext, key));
        root.put("masked", AesGcmCipher.mask(plaintext));

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(fixture.toFile(), root);
        System.out.println("宸叉洿鏂版祴璇曞悜閲忥細" + fixture.toAbsolutePath());
        System.out.println("ciphertext=" + ciphertext);
    }

    /** 缈昏浆瀵嗘枃绗竴涓瓧鑺傦細GCM 鐨?tag 鏍￠獙蹇呴』鍥犳澶辫触銆?*/
    private static String tamper(String ciphertext, byte[] key) {
        String[] parts = ciphertext.split(":", -1);
        byte[] sealed = Base64.getDecoder().decode(parts[2]);
        sealed[0] ^= 0x01;
        return parts[0] + ":" + parts[1] + ":" + Base64.getEncoder().encodeToString(sealed);
    }
}
