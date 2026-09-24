package com.stellarink.aiclient.contract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stellarink.aiclient.dto.IndexJobDTO;
import com.stellarink.aiclient.dto.IndexRebuildRequestDTO;
import com.stellarink.aiclient.dto.QaAnswerDTO;
import com.stellarink.aiclient.dto.QaStreamRequestDTO;
import com.stellarink.aiclient.dto.WritingSuggestRequestDTO;
import com.stellarink.aiclient.dto.WritingSuggestResultDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约测试：Java DTO ↔ Python Pydantic 必须能互读**同一组** JSON。
 *
 * <p>fixture 位于 {@code stellar-ink-ai/tests/fixtures/}，Python 侧
 * {@code tests/test_schemas.py} 做同方向校验。任何一侧改了字段名、枚举字面量或嵌套结构，
 * 两个模块的测试会同时失败 —— 这是 M0 的验收条件之一。
 *
 * <p>断言用 {@link JsonNode}（结构 + 值相等，数字不看宽度、对象不看待键序）：
 * 如果比较 {@code Map<String,Object>}，Jackson 会把 {@code postId} 解析成 Integer、
 * 而 DTO 里是 Long，会因「JSON 数字宽度」误报契约不一致。
 */
class AiContractTest {

    /** 相对本模块根目录（ai-client 与 ai-service 同深度） */
    private static final Path FIXTURES = Path.of("..", "..", "stellar-ink-ai", "tests", "fixtures");

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            // Python 契约是 extra="forbid"：Java 侧同样拒绝未知字段，避免契约悄悄漂移
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // 无类型数字一律按 Long/Double 读：否则 Map/JsonNode 里会出现 Integer 与 DTO 的 Long
            // 因「JSON 数字宽度」被判不相等，掩盖真正的契约差异
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            // 时间必须写成 ISO 8601 字符串：默认会写成 epoch 秒的小数，Python 侧收到的是数字
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .addModule(new JavaTimeModule())
            .build();

    /**
     * 读文件并解析成 DTO。
     *
     * <p>用 {@code readTree(File)} 而不是 {@code readValue(File, ...)}：后者按平台默认字符集解码，
     * 在中文 Windows（GBK）上会把 fixture 里的中文读成乱码，出问题的地方还很难看出来。
     * {@code readTree} 的编码嗅探对 UTF-8 是正确的。
     */
    private static <T> T parse(String fileName, Class<T> type) throws IOException {
        JsonNode tree = MAPPER.readTree(FIXTURES.resolve(fileName).toFile());
        return MAPPER.treeToValue(tree, type);
    }

    /** 读原文 → DTO → 再序列化 → 与原文做结构化比较。 */
    private static <T> T roundTrip(String fileName, Class<T> type) throws IOException {
        JsonNode original = MAPPER.readTree(FIXTURES.resolve(fileName).toFile());
        T parsed = MAPPER.treeToValue(original, type);
        JsonNode serialized = MAPPER.valueToTree(parsed);

        assertContractsMatch(fileName, original, serialized);
        return parsed;
    }

    /**
     * 规范化后比较：时间字段按**同一时刻**判定，其余按结构 + 值判定。
     *
     * <p>为什么要单独处理时间：Python 输出带本地偏移（{@code +08:00}），Java 的
     * {@code Instant} 序列化固定用 UTC（{@code Z}）—— 两者是同一时刻，字面量不同。
     * 若直接比字符串，契约测试会因为「时区写法」常年红灯，反而掩盖真正的字段漂移。
     */
    private static void assertContractsMatch(String fileName, JsonNode expected, JsonNode actual) {
        assertEquals(
                normalize(expected),
                normalize(actual),
                fileName + " 与 Java DTO 的契约不一致");
    }

    private static JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode result = MAPPER.createObjectNode();
            node.fields().forEachRemaining(entry ->
                    result.set(entry.getKey(), normalize(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode result = MAPPER.createArrayNode();
            node.forEach(child -> result.add(normalize(child)));
            return result;
        }
        if (node.isTextual() && node.asText().length() >= 20 && node.asText().charAt(10) == 'T') {
            // ISO 8601 时间一律归一到 UTC 瞬时
            return MAPPER.getNodeFactory().textNode(java.time.Instant.parse(node.asText()).toString());
        }
        return node;
    }

    @Test
    @DisplayName("fixture 目录可达（路径常量写错时明确报错，而不是空跑）")
    void fixturesDirectoryExists() {
        assertTrue(Files.isDirectory(FIXTURES), "找不到共享 fixture 目录：" + FIXTURES.toAbsolutePath());
    }

    @Test
    void qaStreamRequestRoundTrips() throws IOException {
        QaStreamRequestDTO request = roundTrip("qa_stream_request.json", QaStreamRequestDTO.class);

        assertEquals("星笺为什么把文章比作星辰？", request.getQuestion());
        assertEquals(5, request.getTopK());
    }

    @Test
    void qaAnswerRoundTrips() throws IOException {
        QaAnswerDTO answer = roundTrip("qa_answer.json", QaAnswerDTO.class);

        List<?> citations = answer.getCitations();
        assertEquals(2, citations.size());
        assertEquals(12L, answer.getCitations().get(0).getPostId());
        assertEquals(3, answer.getCitations().get(0).getChunkIndex());
        assertEquals("stop", answer.getDoneReason().value());
        assertEquals(1376, answer.getUsage().getTotalTokens());
        assertTrue(answer.getEvidenceSufficient());
        // 正文里的换行必须原样保留（Python 输出 "…。\n\n这一设定…"）
        assertTrue(answer.getAnswer().contains("\n\n"), "答案中的换行被吃掉了");
    }

    @Test
    void writingSuggestRequestRoundTrips() throws IOException {
        WritingSuggestRequestDTO request =
                roundTrip("writing_suggest_request.json", WritingSuggestRequestDTO.class);

        assertEquals("polish", request.getTask().value());
        assertEquals("restrained", request.getTone().value());
        assertEquals(2, request.getCandidateCount());
    }

    @Test
    void writingSuggestResultRoundTrips() throws IOException {
        WritingSuggestResultDTO result =
                roundTrip("writing_suggest_result.json", WritingSuggestResultDTO.class);

        assertEquals(2, result.getCandidates().size());
        assertEquals("polish", result.getTask().value());
    }

    @Test
    void indexRebuildRequestRoundTrips() throws IOException {
        IndexRebuildRequestDTO request =
                roundTrip("index_rebuild_request.json", IndexRebuildRequestDTO.class);

        assertEquals("post_rebuild", request.getKind().value());
        assertEquals(12L, request.getPostId());
    }

    @Test
    void indexJobRoundTrips() throws IOException {
        IndexJobDTO job = roundTrip("index_job.json", IndexJobDTO.class);

        assertEquals("partial", job.getStatus().value());
        assertEquals(1, job.getFailedPosts());
        // 带时区偏移的 ISO 8601 必须能解析（Python 输出 +08:00 → 同一时刻的 UTC）
        assertEquals(
                java.time.Instant.parse("2026-09-24T13:00:00Z"),
                job.getCreatedAt());
        assertEquals(
                java.time.Instant.parse("2026-09-24T13:02:13Z"),
                job.getFinishedAt());
    }

    @Test
    @DisplayName("契约外字段必须报错，不能静默吞掉（两侧同为 forbid）")
    void unknownFieldIsRejected() throws IOException {
        JsonNode tree = MAPPER.readTree(FIXTURES.resolve("qa_stream_request.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("unexpectedField", "不该被静默接收");

        assertThrows(
                UnrecognizedPropertyException.class,
                () -> MAPPER.treeToValue(tree, QaStreamRequestDTO.class));
    }

    @ParameterizedTest
    @CsvSource({
            "STOP,stop",
            "LENGTH,length",
            "REFUSED,refused",
            "CANCELLED,cancelled",
            "ERROR,error",
    })
    @DisplayName("枚举序列化取小写字面量（默认会写成大写，Python 认不出）")
    void doneReasonUsesLowerCaseLiteral(String enumName, String expected) throws IOException {
        var value = Enum.valueOf(com.stellarink.aiclient.enums.DoneReason.class, enumName);

        assertEquals("\"" + expected + "\"", MAPPER.writeValueAsString(value));
    }

    @ParameterizedTest
    @CsvSource({
            "TITLE,title",
            "OUTLINE,outline",
            "CONTINUE,continue",
            "POLISH,polish",
            "TAGS,tags",
            "SUMMARY,summary",
    })
    void writingTaskUsesLowerCaseLiteral(String enumName, String expected) throws IOException {
        var value = Enum.valueOf(com.stellarink.aiclient.enums.WritingTask.class, enumName);

        assertEquals("\"" + expected + "\"", MAPPER.writeValueAsString(value));
    }

    @Test
    @DisplayName("fixture 里的键名不得出现蛇形（出现即说明某侧私自换了命名）")
    void fixtureKeysAreCamelCase() throws IOException {
        Set<String> files = Set.of(
                "qa_stream_request.json",
                "qa_answer.json",
                "writing_suggest_request.json",
                "writing_suggest_result.json",
                "index_rebuild_request.json",
                "index_job.json",
                "error_body.json");

        for (String fileName : files) {
            JsonNode tree = MAPPER.readTree(FIXTURES.resolve(fileName).toFile());
            for (String key : iterable(tree.fieldNames())) {
                assertTrue(!key.contains("_"), fileName + " 出现蛇形键：" + key);
            }
        }
    }

    private static <T> Iterable<T> iterable(java.util.Iterator<T> iterator) {
        return () -> iterator;
    }
}
