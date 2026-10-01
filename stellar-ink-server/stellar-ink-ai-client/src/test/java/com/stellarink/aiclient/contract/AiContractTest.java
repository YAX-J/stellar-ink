package com.stellarink.aiclient.contract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stellarink.aiclient.dto.AgentAskRequestDTO;
import com.stellarink.aiclient.dto.AgentAskResultDTO;
import com.stellarink.aiclient.dto.AiTraceDTO;
import com.stellarink.aiclient.dto.AiWikiClaimDTO;
import com.stellarink.aiclient.dto.AiWikiClaimsResultDTO;
import com.stellarink.aiclient.dto.AiWikiRelationDTO;
import com.stellarink.aiclient.dto.AiWikiTopicDTO;
import com.stellarink.aiclient.dto.EvalCaseResultDTO;
import com.stellarink.aiclient.dto.EvalRunRequestDTO;
import com.stellarink.aiclient.dto.EvalRunResponseDTO;
import com.stellarink.aiclient.dto.EvalStrategySpecDTO;
import com.stellarink.aiclient.dto.IndexJobDTO;
import com.stellarink.aiclient.dto.IndexRebuildRequestDTO;
import com.stellarink.aiclient.dto.QaAnswerDTO;
import com.stellarink.aiclient.dto.QaStreamRequestDTO;
import com.stellarink.aiclient.dto.WritingStyleRequestDTO;
import com.stellarink.aiclient.dto.WritingStyleResultDTO;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    @DisplayName("写作画像：请求与结果都能与 Python 契约互通（E1）")
    void writingStyleRoundTrips() throws IOException {
        WritingStyleRequestDTO request =
                roundTrip("writing_style_request.json", WritingStyleRequestDTO.class);
        WritingStyleResultDTO result =
                roundTrip("writing_style_result.json", WritingStyleResultDTO.class);

        assertEquals(1L, request.getAuthorId());
        assertEquals(20, request.resolvedMaxSamples());
        assertTrue(result.getEvidenceSufficient());
        assertNotNull(result.getProfile());
        // 画像里**不含原句**：字组只能是短字组，出现标点就说明有人把句子塞了进来
        for (String phrase : result.getProfile().getCommonPhrases()) {
            assertTrue(phrase.length() >= 3 && phrase.length() <= 6, "字组长度异常：" + phrase);
            assertFalse(
                    phrase.chars().anyMatch(ch -> "。！？，、；：".indexOf(ch) >= 0),
                    "字组里混进了标点：" + phrase);
        }
        assertFalse(result.getProfile().getTransitions().isEmpty());
    }

    @Test
    @DisplayName("画像 DTO 不得多序列化出契约外的键（hasContent 只是给调用方的便利方法）")
    void writingStyleProfileDoesNotLeakHelperFields() throws IOException {
        WritingStyleResultDTO result = parse("writing_style_result.json", WritingStyleResultDTO.class);
        JsonNode profile = MAPPER.valueToTree(result.getProfile());

        // 便利方法漏进 JSON 是个真实存在过的坑：`@JsonProperty(READ_ONLY)` 只挡反序列化，
        // 序列化时它会以方法名出现在报文里，两侧契约随即对不上
        assertFalse(profile.has("hasContent"), "便捷方法漏进了 JSON：@JsonIgnore 掉了？");
        assertFalse(profile.has("content"), "便捷方法以别的名字漏进了 JSON");
        assertTrue(profile.has("commonPhrases"), "字组字段不见了");
        assertTrue(profile.has("medianSentenceChars"), "键名不是驼峰（Jackson 默认应当是驼峰）");
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
    @DisplayName("评测请求：只跑前 4 题的调试配置，开关原样传给 Python")
    void evalRunRequestRoundTrips() throws IOException {
        EvalRunRequestDTO request = roundTrip("eval_run_request.json", EvalRunRequestDTO.class);

        assertEquals("golden_v1", request.getDataset());
        assertEquals(30, request.getMaxCases());
        assertEquals(3, request.getStrategies().size());

        EvalStrategySpecDTO sparse = request.getStrategies().get(0);
        assertEquals("sparse", sparse.getKey());
        assertTrue(sparse.getEnableSparse());
        assertEquals(Boolean.FALSE, sparse.getEnableDense(), "单路 Sparse 不该开向量通路");

        EvalStrategySpecDTO floor = request.getStrategies().get(2);
        assertEquals("sparse+floor", floor.getKey());
        assertEquals(11.0, floor.getMinScore());
        assertEquals(0.5, floor.getMinScoreRatio());
    }

    @Test
    @DisplayName("评测响应：对比表 + 逐题明细都要能反序列化（面板直接渲染）")
    void evalRunResponseRoundTrips() throws IOException {
        EvalRunResponseDTO response = roundTrip("eval_run_response.json", EvalRunResponseDTO.class);

        // 样例固定由离线桩生成（契约样例必须任何机器都能复现），
        // 但字段本身有三种取值：fake（离线桩）/ panel（面板配的真实模型）/ none（只跑稀疏）
        assertEquals("fake", response.getModels(), "样例取自离线路径：Fake 向量，不代表真实语义质量");
        assertEquals(29, response.getCorpusPosts());
        assertEquals(4, response.getKs().size());
        assertEquals(5, response.getStrategies().size());
        assertEquals("sparse+floor", response.getStrategies().get(4).getKey());
        assertTrue(response.getNotes().stream().anyMatch(note -> note.contains("FakeProvider")));

        // 对比表形状：每组策略都有一份指标，且含面板要展示的列
        Map<String, Object> sparse = response.getPerStrategy().get("sparse");
        assertNotNull(sparse, "对比表缺少 sparse 列");
        assertTrue(sparse.containsKey("recall@1"));
        assertTrue(sparse.containsKey("refusalRate"));

        // 逐题明细：5 组策略 × 4 题
        assertEquals(20, response.getCases().size());
        EvalCaseResultDTO first = response.getCases().get(0);
        assertEquals("q001", first.getCaseId());
        assertEquals("answerable", first.getCaseType());
        assertEquals(Boolean.FALSE, first.getRefused());
        assertFalse(first.getRetrievedPosts().isEmpty(), "有答案题应当检索到文章");
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
    @DisplayName("只读 Agent：预算触顶仍带回引用（不能因为 answer 为空就丢掉 citations）")
    void agentAskRoundTrips() throws IOException {
        AgentAskRequestDTO request = roundTrip("agent_ask_request.json", AgentAskRequestDTO.class);
        AgentAskResultDTO result = roundTrip("agent_ask_result.json", AgentAskResultDTO.class);

        assertEquals("一年写十八万字的方法是什么？", request.getQuestion());
        assertEquals(4, request.getMaxSteps());
        assertEquals("length", result.getDoneReason());
        assertEquals("", result.getAnswer(), "预算触顶时答案为空是**正常**形态");
        assertEquals(2, result.getCitations().size(), "没收敛也必须带回已经查到的引用");
        assertEquals(2, result.getSteps().size());
        assertEquals("budget", result.getInterruptedBy());
        assertTrue(
                result.getSteps().stream().anyMatch(step -> step.getError() != null && !step.getError().isEmpty()),
                "格式不符那一步要留下原因，否则前端看不到「为什么没收敛」");
    }

    @Test
    @DisplayName("链路回放：事件字段由 Python 定义，Java 只搬运（形状靠这份 fixture 守住）")
    void traceReplayRoundTrips() throws IOException {
        AiTraceDTO trace = roundTrip("trace_replay_response.json", AiTraceDTO.class);

        assertEquals("0123456789abcdef0123456789abcdef", trace.getTraceId());
        assertTrue(trace.getFound(), "样例是「这一台记到了这条链路」的形态");
        assertEquals(2, trace.getEvents().size());

        Set<String> kinds = trace.getEvents().stream()
                .map(event -> String.valueOf(event.get("kind")))
                .collect(Collectors.toSet());
        assertEquals(Set.of("retrieval", "model"), kinds, "样例必须覆盖两条链路");

        // Java 不为事件建 DTO 树，但**不代表读不到**：键名就是这份 fixture 守的
        Map<String, Object> retrieval = trace.getEvents().get(0);
        assertEquals(3L, retrieval.get("topK"));
        assertEquals(Boolean.FALSE, retrieval.get("refused"));
        Map<String, Object> model = trace.getEvents().get(1);
        assertEquals("fixture-chat", model.get("model"));
        assertEquals(49L, model.get("promptTokens"));
    }

    @Test
    @DisplayName("Wiki 主张：证据四件套 + 原文片段都要能读出来（这就是「能回到原文」的凭据）")
    void wikiClaimsRoundTrips() throws IOException {
        AiWikiClaimsResultDTO result =
                roundTrip("wiki_claims_result.json", AiWikiClaimsResultDTO.class);

        assertEquals(3, result.getClaims().size());
        assertEquals(4, result.getStats().getProposed());
        assertEquals(3, result.getStats().getKept());
        assertEquals(
                Map.of("quoteNotFound", 1, "entityNotInText", 1),
                result.getStats().getDropped(),
                "被证据校验挡下多少必须在契约里：它是「模型不行 vs 编造被挡」的唯一线索；"
                        + "实体与主张各有自己的丢弃原因");

        AiWikiClaimDTO claim = result.getClaims().get(0);
        assertNotNull(claim.getPostId());
        assertNotNull(claim.getChunkIndex());
        assertNotNull(claim.getPostVersion(), "文章版本：文章改了这条主张就该重算");
        assertNotNull(claim.getContentHash(), "段落哈希：只失效受影响的那几条");
        assertEquals("每天写五百字，一年就是十八万字", claim.getQuote());
        assertTrue(claim.getConfidence() > 0);
        assertTrue(result.getNotes().stream().anyMatch(note -> note.contains("引用找不到原文依据")));

        // 实体（E4-4）：写法差异合并成一个，且每个提及都能回到某条主张
        assertEquals(5, result.getStats().getEntityProposed());
        assertEquals(4, result.getStats().getEntityKept());
        assertEquals(3, result.getStats().getEntities());
        assertEquals(3, result.getEntities().size());
        assertEquals("每天写五百字", result.getEntities().get(0).getName());
        assertEquals(2, result.getEntities().get(0).getCount(), "空白/全角差异应当合并");
        assertEquals(List.of(7L), result.getEntities().get(0).getPostIds());
        assertTrue(
                result.getEntities().stream()
                        .flatMap(entity -> entity.getMentions().stream())
                        .allMatch(mention -> mention.getClaimText() != null
                                && !mention.getClaimText().isEmpty()),
                "每个实体提及都要挂在一条具体主张上 —— 那是它回到证据的那条线");

        // 共现关系（E4-5）：无向边只有一种表示，且**边也带证据**
        assertEquals(1, result.getStats().getRelations());
        assertEquals(1, result.getRelations().size());
        AiWikiRelationDTO relation = result.getRelations().get(0);
        assertTrue(
                relation.getSource().compareTo(relation.getTarget()) < 0,
                "无向边按字典序存，否则 (A,B)/(B,A) 会各存一行、权重看着只有一半");
        assertEquals(1, relation.getWeight());
        assertFalse(relation.getEvidence().isEmpty(), "边必须能回到原文");
        assertEquals(
                "每天写五百字，一年可以累积十八万字",
                relation.getEvidence().get(0).getClaimText());

        // 主题（E4-8）：连通分量；名字是关键词组合，且主题页带**可核对的原文**
        assertEquals(1, result.getStats().getTopics());
        assertEquals(1, result.getTopics().size());
        AiWikiTopicDTO topic = result.getTopics().get(0);
        assertEquals(2, topic.getSize());
        assertEquals(1, topic.getWeight());
        assertEquals(Set.of("每天写五百字", "十八万字"), Set.copyOf(topic.getEntities()));
        assertTrue(topic.getKeywords().size() <= topic.getEntities().size(), "关键词必须来自本主题的实体");
        assertEquals(List.of(7L), topic.getPostIds());
        assertEquals(
                "每天写五百字，一年可以累积十八万字", topic.getEvidence().get(0).getClaimText());
    }

    @Test
    @DisplayName("fixture 里的键名不得出现蛇形（出现即说明某侧私自换了命名）")
    void fixtureKeysAreCamelCase() throws IOException {
        Set<String> files = Set.of(
                "qa_stream_request.json",
                "qa_answer.json",
                "writing_suggest_request.json",
                "writing_suggest_result.json",
                "writing_style_request.json",
                "writing_style_result.json",
                "agent_ask_request.json",
                "agent_ask_result.json",
                "index_rebuild_request.json",
                "index_job.json",
                "eval_run_request.json",
                "eval_run_response.json",
                "trace_replay_response.json",
                "wiki_claims_result.json",
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
