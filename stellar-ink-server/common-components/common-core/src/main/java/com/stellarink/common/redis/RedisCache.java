package com.stellarink.common.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 基于 {@link RedisUtils} 的旁路缓存封装。
 *
 * <p>缓存读取、写入和失效均采用故障放行策略：Redis 短暂不可用时记录告警并回源，
 * 不让非关键缓存故障中断业务请求。登录锁定等安全状态仍应直接使用 {@link RedisUtils}。
 *
 * <h2>指标（可观测性）</h2>
 * 旁路缓存的故障是**静默**的：Redis 挂掉时这里会熔断 30 秒、所有请求直接回源数据库，
 * 而业务完全不报错（这是刻意的设计，见下面的 {@code suspend()}）。所以「缓存挂了」
 * 这件事只能靠指标发现，日志里只有一行 warn：
 * <ul>
 *   <li>{@code stellar.cache.requests{namespace,result}} —— result 取
 *       {@code hit} / {@code miss} / {@code error} / {@code bypass}。
 *       {@code bypass} 是「本次根本没访问 Redis」（处于熔断窗口内），
 *       它与 {@code miss} 必须分开：前者是 Redis 不可用，后者是缓存里确实没有。</li>
 *   <li>{@code stellar.cache.suspended} —— Gauge 0/1，1 表示正处于 30 秒熔断窗口。
 *       这是缓存层唯一的「静默故障」信号。</li>
 *   <li>{@code stellar.cache.evictions} / {@code stellar.cache.invalidations} ——
 *       按命名空间统计的删除与版本推进，用来解释「命中率为什么突然掉了」。</li>
 * </ul>
 *
 * <p><b>命名空间标签是必须的</b>：所有缓存共用一个 Redis，不分命名空间就只能看到
 * 「总命中率」，而「post 列表命中率 5%」这种具体问题会被平均掉。
 *
 * <p>⚠️ <b>装配约束</b>：本类需要 {@link MeterRegistry}。它只在 user / content 两个
 * Servlet 业务服务的全量上下文里装配；ai-service 的 12 个 {@code @WebMvcTest} 切片
 * 已在启动类里显式排除本类（切片里没有 MeterRegistry 自动配置）。
 * <b>将来若给 user / content 加切片测试，必须同样排除或 mock 本类</b>，
 * 否则整个切片上下文起不来。
 */
@Slf4j
@Component
public class RedisCache {

    /** 未显式指定命名空间时的标签值（例如 user-service 的公开作者摘要）。 */
    public static final String DEFAULT_NAMESPACE = "default";

    private static final String METRIC_REQUESTS = "stellar.cache.requests";
    private static final String METRIC_SUSPENDED = "stellar.cache.suspended";
    private static final String METRIC_EVICTIONS = "stellar.cache.evictions";
    private static final String METRIC_INVALIDATIONS = "stellar.cache.invalidations";

    private static final String TAG_NAMESPACE = "namespace";
    private static final String TAG_RESULT = "result";

    /** 读缓存的结果分类。取值会原样出现在 Prometheus 标签里，改名字等于改看板。 */
    private static final String RESULT_HIT = "hit";
    private static final String RESULT_MISS = "miss";
    private static final String RESULT_ERROR = "error";
    private static final String RESULT_BYPASS = "bypass";

    private static final long FAILURE_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final RedisUtils redisUtils;
    private final MeterRegistry meterRegistry;
    private final AtomicLong suspendedUntilNanos = new AtomicLong();

    public RedisCache(RedisUtils redisUtils, MeterRegistry meterRegistry) {
        this.redisUtils = redisUtils;
        this.meterRegistry = meterRegistry;
        // Gauge 持有的是**弱引用**，这里被引用的是 Spring 单例 Bean，不会被回收。
        Gauge.builder(METRIC_SUSPENDED, this, cache -> cache.isSuspended() ? 1d : 0d)
                .description("缓存是否处于失败熔断窗口：1=Redis 不可用，所有缓存操作直接回源（业务不报错）")
                .register(meterRegistry);
    }

    public <T> T get(String key, Class<T> type) {
        return get(DEFAULT_NAMESPACE, key, type);
    }

    public <T> T get(String namespace, String key, Class<T> type) {
        return read(namespace, () -> redisUtils.get(key, type));
    }

    public <T> T get(String key, TypeReference<T> type) {
        return get(DEFAULT_NAMESPACE, key, type);
    }

    public <T> T get(String namespace, String key, TypeReference<T> type) {
        return read(namespace, () -> redisUtils.get(key, type));
    }

    public <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        return getOrLoad(DEFAULT_NAMESPACE, key, type, ttl, loader);
    }

    public <T> T getOrLoad(String namespace, String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        T cached = get(namespace, key, type);
        if (cached != null) {
            return cached;
        }
        return loadAndCache(namespace, key, ttl, loader);
    }

    public <T> T getOrLoad(String key, TypeReference<T> type, Duration ttl, Supplier<T> loader) {
        return getOrLoad(DEFAULT_NAMESPACE, key, type, ttl, loader);
    }

    public <T> T getOrLoad(String namespace, String key, TypeReference<T> type, Duration ttl, Supplier<T> loader) {
        T cached = get(namespace, key, type);
        if (cached != null) {
            return cached;
        }
        return loadAndCache(namespace, key, ttl, loader);
    }

    public void put(String key, Object value, Duration ttl) {
        put(DEFAULT_NAMESPACE, key, value, ttl);
    }

    public void put(String namespace, String key, Object value, Duration ttl) {
        if (value == null || isSuspended()) {
            return;
        }
        try {
            redisUtils.set(key, value, ttl);
            resume();
        } catch (RuntimeException ex) {
            suspend();
            log.warn("写入 Redis 缓存失败，忽略本次缓存 namespace={} key={} error={}",
                    namespace, key, ex.toString());
        }
    }

    public void evict(String key) {
        evict(DEFAULT_NAMESPACE, key);
    }

    public void evict(String namespace, String key) {
        if (isSuspended()) {
            return;
        }
        try {
            redisUtils.delete(key);
            resume();
            count(METRIC_EVICTIONS, namespace, null);
        } catch (RuntimeException ex) {
            suspend();
            log.warn("清理 Redis 缓存失败，等待有效期自然淘汰 namespace={} key={} error={}",
                    namespace, key, ex.toString());
        }
    }

    /**
     * 读取缓存命名空间版本。版本键不存在时使用 0，不主动创建无意义的键。
     *
     * <p>⚠️ <b>这个读操作刻意不计入命中率</b>：版本键从不被写入（不存在即视为 0 是合法状态），
     * 所以它的「未命中」是**正常状态而不是缓存失效**，而且每个请求都会发生一次。
     * 把它计进 {@code stellar.cache.requests{result="miss"}} 会让整体命中率被永久拉低，
     * 看板上的数字就再也反映不了真实缓存效果了。
     */
    public long version(String versionKey) {
        // 传 countMetrics=false：见上面的说明，版本键的「未命中」是正常状态
        Long version = read(DEFAULT_NAMESPACE, false, () -> redisUtils.get(versionKey, Long.class));
        return version == null ? 0L : version;
    }

    /**
     * 原子推进命名空间版本，使旧版本下的任意参数缓存立即不可达。
     */
    public void invalidateVersion(String versionKey) {
        invalidateVersion(DEFAULT_NAMESPACE, versionKey);
    }

    /**
     * 带命名空间标签的版本推进（命名空间只用于指标标签，键本身仍由调用方拼好）。
     */
    public void invalidateVersion(String namespace, String versionKey) {
        if (isSuspended()) {
            return;
        }
        try {
            redisUtils.increment(versionKey, 1L);
            resume();
            count(METRIC_INVALIDATIONS, namespace, null);
        } catch (RuntimeException ex) {
            suspend();
            log.warn("推进 Redis 缓存版本失败，旧值最多保留到有效期结束 namespace={} key={} error={}",
                    namespace, versionKey, ex.toString());
        }
    }

    /**
     * 读缓存的统一入口：**命中 / 未命中 / 出错 / 熔断跳过** 四档只在这里判定。
     *
     * <p>四档分开记是刻意的：把它们合成一个「缓存失败率」之后，
     * 「Redis 挂了（bypass 涨）」和「缓存里没有这条数据（miss 涨）」会看起来一模一样，
     * 而这两件事的处理方式完全相反。
     */
    private <T> T read(String namespace, Supplier<T> readFromRedis) {
        return read(namespace, true, readFromRedis);
    }

    private <T> T read(String namespace, boolean countMetrics, Supplier<T> readFromRedis) {
        if (isSuspended()) {
            if (countMetrics) {
                count(METRIC_REQUESTS, namespace, RESULT_BYPASS);
            }
            return null;
        }
        try {
            T value = readFromRedis.get();
            resume();
            if (countMetrics) {
                count(METRIC_REQUESTS, namespace, value == null ? RESULT_MISS : RESULT_HIT);
            }
            return value;
        } catch (RuntimeException ex) {
            suspend();
            if (countMetrics) {
                count(METRIC_REQUESTS, namespace, RESULT_ERROR);
            }
            log.warn("读取 Redis 缓存失败，回源业务数据 namespace={} error={}", namespace, ex.toString());
            return null;
        }
    }

    private <T> T loadAndCache(String namespace, String key, Duration ttl, Supplier<T> loader) {
        Objects.requireNonNull(loader, "缓存回源函数不能为空");
        T loaded = loader.get();
        put(namespace, key, loaded, ttl);
        return loaded;
    }

    private void count(String metricName, String namespace, String result) {
        var builder = Counter.builder(metricName)
                .tag(TAG_NAMESPACE, namespace);
        if (result != null) {
            builder.tag(TAG_RESULT, result);
        }
        builder.description(descriptionOf(metricName))
                .register(meterRegistry)
                .increment();
    }

    private static String descriptionOf(String metricName) {
        return switch (metricName) {
            case METRIC_REQUESTS -> "旁路缓存读请求：result=hit/miss/error/bypass"
                    + "（bypass=处于故障熔断窗口，本次没有访问 Redis）";
            case METRIC_EVICTIONS -> "按命名空间删除的缓存键数量";
            case METRIC_INVALIDATIONS -> "按命名空间推进版本号（整体失效）的次数";
            default -> "星笺缓存指标";
        };
    }

    private boolean isSuspended() {
        return System.nanoTime() < suspendedUntilNanos.get();
    }

    private void suspend() {
        suspendedUntilNanos.set(System.nanoTime() + FAILURE_BACKOFF_NANOS);
    }

    private void resume() {
        suspendedUntilNanos.set(0L);
    }
}
