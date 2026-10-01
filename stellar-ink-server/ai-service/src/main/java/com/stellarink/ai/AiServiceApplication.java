package com.stellarink.ai;

import com.stellarink.common.redis.RedisCache;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
 *   <li>模型配置的 CRUD 与密钥加解密（{@code ai_* } 表归 AI 域所有）</li>
 *   <li>通过 {@code stellar-ink-ai-client} 调用 Python（仅内网，浏览器不可直达）</li>
 * </ul>
 *
 * <p>本服务**不**直接读写 {@code post} / {@code user} 等业务表：文章数据由 content-service
 * 的内部契约提供（M3），草稿只随当前作者请求临时传输。它拥有的是 {@code ai_*} 表
 * （{@code ai_provider_config} 等），且**只**访问这些表。
 *
 * <p>为什么仍然排除 {@code RedisCache}：它是「读缓存」工具，本服务不做业务数据缓存。
 * {@code RedisUtils} 自 E3-2（AI 配额与并发闸门）起**不再排除** —— 配额需要一个原子计数器，
 * 而 {@code StringRedisTemplate} 由 spring-data-redis 的自动配置提供；不用就不建连接。
 * 数据源与 MyBatis-Plus 则需要（模型配置与调用账都要落库）。
 *
 * <p>这里刻意**不用** {@code @MapperScan}：那会在应用类上留下一个全局 Mapper 扫描器，
 * 连 {@code @WebMvcTest} 这种只加载 Web 层的切片测试也会去建 Mapper、进而要求 {@code SqlSessionFactory}，
 * 把纯 Web 测试变成「必须连库」。改为在每个 Mapper 接口上标 {@code @Mapper}，
 * 语义一样，但切片测试能干净地只测 Web。
 */
@SpringBootApplication
@ComponentScan(
        basePackages = {"com.stellarink.ai", "com.stellarink.common", "com.stellarink.aiclient"},
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RedisCache.class),
        })
@EnableDiscoveryClient
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
