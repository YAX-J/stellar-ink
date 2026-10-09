package com.stellarink.gateway.filter;

import com.stellarink.sharedmodel.auth.TokenRevocationKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 在 Sa-Token 路由鉴权前拦截已登出或改密后撤销的 JWT。 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RevokedTokenFilter implements WebFilter {

    private static final String UNAUTHORIZED_BODY = "{\"code\":401,\"msg\":\"会话已失效，请重新登录\"}";

    /**
     * 撤销校验结果计数：{@code stellar.auth.revoked.check{result}}。
     *
     * <p>这是整条链路上**最该被监控的一个指标**。原因：撤销校验是 fail-closed 的
     * —— Redis 查不通时网关回 503 而不是放行。安全口径正确，但**用户看到的是
     * 「莫名其妙 503」**：一次连接抖动 → 一个请求 503 → 下一个又好了，
     * 而日志里只有一行 warn，响应体里过去什么线索都没有。
     * 四档分开记，就是为了让「为什么这次 503」不再需要一个一个去猜：
     * <ul>
     *   <li>{@code ok} —— 查询成功且未撤销，正常放行</li>
     *   <li>{@code revoked} —— 命中撤销列表，返回 401（用户主动登出/改密，属正常）</li>
     *   <li>{@code error} —— 两次查询都失败，fail-closed 返回 503（**Redis 链路问题**）</li>
     *   <li>{@code timeout} —— 撞上 2 秒整体上限，fail-closed 返回 503
     *       （**连接池被占满 / 连接卡死**，与上一条的修法不同，所以要分开）</li>
     * </ul>
     */
    private static final String METRIC_CHECK = "stellar.auth.revoked.check";

    /** 撤销校验耗时：{@code stellar.auth.revoked.duration{result}}。 */
    private static final String METRIC_DURATION = "stellar.auth.revoked.duration";

    /** 触发重试的次数：{@code stellar.auth.revoked.retry}。见下面 filter 里的说明。 */
    private static final String METRIC_RETRY = "stellar.auth.revoked.retry";

    private static final String TAG_RESULT = "result";

    private static final String RESULT_OK = "ok";
    private static final String RESULT_REVOKED = "revoked";
    private static final String RESULT_ERROR = "error";
    private static final String RESULT_TIMEOUT = "timeout";

    /** 重试间隔：够 Lettuce 完成一次重连，又不至于让用户明显感到卡顿 */
    private static final Duration RETRY_DELAY = Duration.ofMillis(120);

    /**
     * 撤销校验的**整体**上限。
     *
     * <p>为什么必须有它（前端截图里那两个请求 {@code (canceled) @15s} 就是没有它的后果）：
     * 命令超时（dev 500ms）只约束「命令」，不约束**从连接池拿连接**——
     * 响应式路径走的是 Lettuce 的 {@code asyncPools}，而
     * {@code CommonsPool2ConfigConverter} 并不把 {@code max-wait} 搬过去，
     * 于是池子被占满（或连接全卡死在黑洞里）时 {@code acquire()} 可以**永远不返回**。
     * 那时网关一直挂着，用户要等浏览器自己的 15s 超时才看到「请求超时」，
     * 而我们对为什么超时一无所知。
     *
     * <p>2s 的账：单次命令超时 500ms + 重试间隔 120ms + 第二次 500ms ≈ 1.2s，
     * 再留一点余量；两次都回不来就按 fail-closed 回 503（带 hint），
     * 用户立刻看到「哪一环断了」而不是干等 15 秒。
     */
    private static final Duration SESSION_CHECK_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 撤销列表查不到时的响应体。
     *
     * <p>三处修正（都踩过）：
     * <ul>
     *   <li>以前的 {@code code} 写的是 500，而 HTTP 状态是 503 —— 前端按 code 判类型会看错档；</li>
     *   <li>以前没有任何线索，用户只能看到「莫名其妙 503」。现在写清是**哪一环**断了，
     *       并给出下一步（这条链路最常见的故障是「跨公网连 Redis + 500ms 超时」）；</li>
     *   <li>以前这里同时兜住了**下游路由**的异常（见 {@link #filter}），
     *       于是「Nacos 里没有 ai-service 实例」也被报成「会话校验服务不可用」——
     *       日志与响应一起指向 Redis，而 Redis 其实是好的。</li>
     * </ul>
     */
    private static final String UNAVAILABLE_BODY =
            "{\"code\":503,\"msg\":\"会话校验服务不可用：网关连不上 Redis 撤销列表\","
                    + "\"hint\":\"检查 REDIS_HOST/REDIS_PORT 是否可达与超时设置；"
                    + "本地开发建议指向本机 Redis（跨公网的 500ms 超时会让带 token 的请求随机 503）\"}";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    public RevokedTokenFilter(ReactiveStringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isSessionCreation(exchange)) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(token)) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();

        // 耗时的起点：只覆盖「这次撤销校验」，不含被放行之后的业务处理
        long startedAtNanos = System.nanoTime();

        // 每次请求一份状态：用来把「重试」只计一次。
        // 不能直接用 doOnError 计数 —— 两次都失败时它会触发两遍，
        // 于是「一次重试」会被记成 2，看板上的抖动次数直接翻倍。
        AtomicBoolean retried = new AtomicBoolean(false);

        // ⚠️ 错误处理**只能包住这一次 Redis 查询**：把它挂在 flatMap 之前。
        // 以前写在整条链的最后，于是 chain.filter(exchange) 里抛出的任何异常
        // （最典型的是「Unable to find instance for xxx」）都会被这里吞掉并改写成
        // 「Redis 会话撤销校验失败」—— 排查方向被彻底带偏。
        //
        // 关于那次重试：撤销列表在远端 Redis（跨公网，实测往返 ~36ms / 超时 500ms），
        // 稳态余量充足，真正会失败的是**连接被掐断、Lettuce 正在重连**的那一瞬间 ——
        // 一个请求 503、下一个又好了，这就是「莫名其妙」的来源。
        // 重试一次（间隔 120ms）足以跨过重连窗口；两次都失败仍然 fail-closed，
        // 安全口径不变，只是不再让一次网络抖动变成一次 503。
        Mono<Boolean> revoked = redisTemplate.hasKey(TokenRevocationKey.of(token))
                .doOnError(ex -> {
                    if (retried.compareAndSet(false, true)) {
                        // 只是「即将重试」的计数：它是 503 的**前兆**，
                        // 重试通常能跨过去，所以此时用户还没受影响。
                        count(METRIC_RETRY, null);
                    }
                    log.warn("Redis 撤销列表查询失败，重试一次：path={} error={}", path, ex.toString());
                })
                .retryWhen(Retry.fixedDelay(1, RETRY_DELAY))
                // 整体上限：跨过「命令超时 + 重试」还没回来就快速失败，别把请求挂到浏览器的 15s
                .timeout(SESSION_CHECK_TIMEOUT)
                .doOnError(ex -> log.error(
                        "Redis 撤销列表两次都失败，按 fail-closed 返回 503：path={} error={}",
                        path,
                        ex.toString()))
                .onErrorMap(SessionCheckUnavailableException::new);

        return revoked
                .flatMap(value -> {
                    if (Boolean.TRUE.equals(value)) {
                        record(RESULT_REVOKED, startedAtNanos);
                        return write(exchange, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_BODY);
                    }
                    record(RESULT_OK, startedAtNanos);
                    return chain.filter(exchange);
                })
                .onErrorResume(SessionCheckUnavailableException.class, ex -> {
                    // 两种失败要分开：error = 命令/连接错误（重试也没用），
                    // timeout = 撞上 2 秒整体上限（典型是响应式连接池 acquire 拿不到连接）。
                    // 它们的修法不同（前者查网络与 Redis 本身，后者查池子与卡死的连接），
                    // 所以指标上也必须分开。
                    record(ex.getCause() instanceof TimeoutException ? RESULT_TIMEOUT : RESULT_ERROR,
                            startedAtNanos);
                    return write(exchange, HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_BODY);
                });
    }

    /** 只用于区分「Redis 这一次查询失败」与「下游路由失败」，不对外暴露。 */
    static class SessionCheckUnavailableException extends RuntimeException {

        SessionCheckUnavailableException(Throwable cause) {
            super(cause);
        }
    }

    private boolean isSessionCreation(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return "POST".equalsIgnoreCase(exchange.getRequest().getMethod().name())
                && ("/auth/login".equals(path) || "/auth/register".equals(path));
    }

    /**
     * 记录一次撤销校验的结果与耗时。
     *
     * <p>耗时**连失败一起记**（把 timeout 也记进去）是刻意的：P99 在 Redis 抖动时
     * 直接顶到 2 秒上限，正是「用户实际感受到了多久」的真实答案。
     * 若只记成功路径，看板会一片祥和，而用户那边在转圈。
     */
    private void record(String result, long startedAtNanos) {
        count(METRIC_CHECK, result);
        Timer.builder(METRIC_DURATION)
                .tag(TAG_RESULT, result)
                .description("网关撤销校验耗时（含失败与 2 秒整体上限；失败样本会顶到上限）")
                .register(meterRegistry)
                .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
    }

    private void count(String metricName, String result) {
        var builder = Counter.builder(metricName);
        if (result != null) {
            builder.tag(TAG_RESULT, result);
        }
        builder.description(descriptionOf(metricName))
                .register(meterRegistry)
                .increment();
    }

    private static String descriptionOf(String metricName) {
        return switch (metricName) {
            case METRIC_CHECK -> "网关撤销校验结果："
                    + "ok=放行 / revoked=命中撤销列表(401) / error=两次都失败(503,Redis 链路) /"
                    + " timeout=撞上 2 秒上限(503,连接池或连接卡死)";
            case METRIC_RETRY -> "撤销校验触发重试的次数（Redis 链路抖动的前兆，通常不影响用户）";
            default -> "星笺网关鉴权指标";
        };
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String body) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
