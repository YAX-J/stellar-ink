package com.stellarink.ai;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.stellarink.common.redis.RedisCache;
import com.stellarink.common.redis.RedisUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * AI 业务服务（对外 {@code /ai/**} 的唯一 Java 出口）。
 *
 * <p>职责边界（docs/ai/implementation-roadmap.md §2）：
 * <ul>
 *   <li>Sa-Token JWT 防御性复核与角色门槛（主门槛在网关，M1 接上路由）</li>
 *   <li>请求限额、审计与统一 {@code Response<T>}</li>
 *   <li>通过 {@code stellar-ink-ai-client} 调用 Python（仅内网，浏览器不可直达）</li>
 * </ul>
 *
 * <p>本服务**不**直接读写 {@code post} / {@code user} 等业务表：文章数据由 content-service
 * 的内部契约提供（M3），草稿只随当前作者请求临时传输。
 *
 * <p>为什么排除公共模块里的三个 Bean：{@code MybatisPlusConfig} 会建 MyBatis-Plus 拦截器、
 * {@code RedisUtils}/{@code RedisCache} 会要求注入 {@code StringRedisTemplate}，而 ai-service
 * 当前**既不拥有数据表也不做缓存**（AI 的 {@code ai_*} 表在 M3 才建，配额与 nonce 在 M1 才用）。
 * 这些类会随 common-core 传递到 classpath（common-core 未标 optional），
 * 排除扫描可以避免服务启动时就去初始化 Redis 连接池；等 M1/M3 真正需要时再随对应切片放开。
 * 服务内自身需要复用的公共组件（全局异常、TraceId、访问日志）保持照常生效。
 *
 * <p>同时显式排除 {@link DataSourceAutoConfiguration}：仓库里其它服务都连 MySQL，
 * 但 AI 服务不该"顺手"拥有数据源 —— 排掉之后，将来若有人无意引入 JDBC 相关代码，
 * 启动就会直接失败，而不是悄悄连上一个数据库（红线 §7.2）。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ComponentScan(
        basePackages = {"com.stellarink.ai", "com.stellarink.common", "com.stellarink.aiclient"},
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = MybatisPlusInterceptor.class),
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RedisUtils.class),
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RedisCache.class),
        })
@EnableDiscoveryClient
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
