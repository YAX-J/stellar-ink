package com.stellarink.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享连接必须被关掉 —— 这是「全站 5~16 秒 503」那条链路的开关。
 *
 * <p>这条断言看着很小，但它守的是一个**只在故障时才看得见**的差异：
 * 开着共享连接时，一条命令超时会让整条流水线的应答错位，之后每个撤销校验都超时；
 * 关掉之后坏连接最多影响一个操作。而它不是配置项（Boot 3.2 没有这个键），
 * 只能由这里的 BeanPostProcessor 设 —— 所以必须有用例盯着，否则哪天有人删了它，
 * 表现会是「偶尔全站 503 又自己好了」，没人能联想到这行代码。
 */
class RedisConnectionTuningConfigTest {

    @Test
    @DisplayName("默认开启共享连接 → 处理后必须变成 false")
    void disablesSharedNativeConnection() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 6379));
        assertTrue(factory.getShareNativeConnection(), "前提：Lettuce 默认就是共享一条连接");

        BeanPostProcessor processor = RedisConnectionTuningConfig.disableSharedNativeConnection();
        Object returned = processor.postProcessBeforeInitialization(factory, "redisConnectionFactory");

        assertFalse(factory.getShareNativeConnection(), "共享连接必须被关掉，否则一条坏连接会拖垮全部请求");
        assertTrue(returned == factory, "后置处理器必须原样返回这个 bean，别把工厂换掉");
    }

    @Test
    @DisplayName("其它 bean 不受影响")
    void leavesOtherBeansAlone() {
        BeanPostProcessor processor = RedisConnectionTuningConfig.disableSharedNativeConnection();
        Object other = new Object();

        assertTrue(processor.postProcessBeforeInitialization(other, "anything") == other);
    }
}
