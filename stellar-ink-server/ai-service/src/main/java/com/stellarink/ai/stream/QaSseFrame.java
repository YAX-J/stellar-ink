package com.stellarink.ai.stream;

/**
 * 一个已经拼好的 SSE 帧。
 *
 * <p>为什么 Java 侧要解析帧而不是把字节流原样抄给浏览器：
 * <ol>
 *   <li><b>日志与审计需要事件类型</b>：只记「转发了 N 字节」没法回答「这次是不是拒答」；
 *       类型在 JSON 里（`data: {"type":"done",…}`），解析出来才能埋点。</li>
 *   <li><b>将来要在中间插帧</b>：配额提示、traceId 回写这类事件得由 Java 生成，
 *       有一个帧对象比拼接字符串安全。</li>
 * </ol>
 *
 * <p>转发时用 {@link #raw()} 而不是重新序列化：**Java 不重新编码 Python 的事件体**，
 * 少一层映射就少一处会与 Python 契约分叉的地方（红线 §7.1）。
 *
 * @param eventType 事件类型（`meta` / `citation` / `delta` / `done` / `error`），解析不到时为 `unknown`
 * @param raw       可以直接写给浏览器的完整帧（含结尾空行）
 */
public record QaSseFrame(String eventType, String raw) {

    /** 事件类型常量：与 Python `schemas/qa_stream.py`、前端解析器逐字一致。 */
    public static final String META = "meta";
    public static final String CITATION = "citation";
    public static final String DELTA = "delta";
    public static final String DONE = "done";
    public static final String ERROR = "error";
    public static final String UNKNOWN = "unknown";

    /** 解析不出类型时的兜底：**不猜**，如实标成 unknown（猜错会让审计数据变成假的）。 */
    public static QaSseFrame of(String raw) {
        return new QaSseFrame(extractType(raw), raw);
    }

    /**
     * 从 `data: {json}` 里抠出 `type` 值。
     *
     * <p>刻意用最小的字符串查找而不是引入 JSON 解析：帧是**我们自己契约**产出的固定形状
     * （见 `docs/api/README.md` 的线格式），为它建一棵对象树既慢又多一份需要同步的映射。
     * 真需要结构化字段时再解析 —— 那时也应该先把事件体定义成 Java DTO。
     */
    static String extractType(String raw) {
        int start = raw.indexOf("\"type\"");
        if (start < 0) {
            return UNKNOWN;
        }
        int colon = raw.indexOf(':', start);
        int quote = colon < 0 ? -1 : raw.indexOf('"', colon + 1);
        if (quote < 0) {
            return UNKNOWN;
        }
        int end = raw.indexOf('"', quote + 1);
        return end < 0 ? UNKNOWN : raw.substring(quote + 1, end);
    }
}
