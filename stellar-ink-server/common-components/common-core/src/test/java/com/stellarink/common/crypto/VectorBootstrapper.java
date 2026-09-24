package com.stellarink.common.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Generates (not hand-writes) the ciphertext inside the cross-language test vector.
 *
 * <p>Why a program: ciphertext cannot be computed by hand, and "each side encrypts and
 * decrypts its own output" can never catch a format mismatch. There has to be one frozen
 * ciphertext that both Java and Python read.
 *
 * <p>Usage, from {@code stellar-ink-server}:
 * <pre>
 * mvn -q -pl common-components/common-core test-compile
 * mvn -q -pl common-components/common-core exec:java -Dexec.classpathScope=test \
 *   -Dexec.mainClass=com.stellarink.common.crypto.CryptoVectorBootstrapper
 * </pre>
 * Commit the regenerated JSON. Regenerate whenever the format changes
 * (version prefix, nonce length, tag placement).
 */
public final class VectorBootstrapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FIXTURE_RELATIVE = "stellar-ink-ai/tests/fixtures/key_vector.json";

    private VectorBootstrapper() {
    }

    /**
     * Locates the shared fixture regardless of the working directory.
     *
     * <p>Surefire runs with the module directory as CWD while {@code exec:java} runs from the
     * reactor root, so a fixed relative path breaks one of them. Walking up to the directory
     * that contains {@code stellar-ink-ai/} works for both.
     */
    static Path locateFixture() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(FIXTURE_RELATIVE);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("fixture not found from " + Path.of("").toAbsolutePath());
    }

    public static void main(String[] args) throws Exception {
        Path fixture = locateFixture();
        if (!Files.exists(fixture)) {
            throw new IllegalStateException("fixture not found: " + fixture.toAbsolutePath());
        }

        ObjectNode root = (ObjectNode) MAPPER.readTree(fixture.toFile());
        byte[] key = MasterKey.load(root.get("masterKeyB64").asText());
        byte[] nonce = Base64.getDecoder().decode(root.get("nonceB64").asText());
        String plaintext = root.get("plaintext").asText();

        String ciphertext = AesGcmCipher.encrypt(plaintext, key, nonce);
        root.put("ciphertext", ciphertext);
        root.put("tamperedCiphertext", tamper(ciphertext));
        root.put("masked", AesGcmCipher.mask(plaintext));

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(fixture.toFile(), root);
        System.out.println("updated fixture: " + fixture.toAbsolutePath());
        System.out.println("ciphertext=" + ciphertext);
    }

    /** Flips one byte of the sealed payload: the GCM tag check must then fail. */
    private static String tamper(String ciphertext) {
        String[] parts = ciphertext.split(":", -1);
        byte[] sealed = Base64.getDecoder().decode(parts[2]);
        sealed[0] ^= 0x01;
        return parts[0] + ":" + parts[1] + ":" + Base64.getEncoder().encodeToString(sealed);
    }
}
