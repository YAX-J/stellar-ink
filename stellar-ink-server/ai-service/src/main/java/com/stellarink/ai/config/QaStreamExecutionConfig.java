package com.stellarink.ai.config;

import cn.dev33.satoken.context.SaTokenContextForThreadLocalStaff;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.request.RequestContextHolder;

/** 流转发使用有界执行器，登录请求与 traceId 显式传递，任务结束后清理线程上下文。 */
@Configuration(proxyBeanMethods = false)
public class QaStreamExecutionConfig {

    @Bean("qaStreamExecutor")
    public ThreadPoolTaskExecutor qaStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ai-qa-stream-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(0);
        executor.setTaskDecorator(requestContextDecorator());
        return executor;
    }

    static TaskDecorator requestContextDecorator() {
        return task -> {
            var request = RequestContextHolder.getRequestAttributes();
            var context = MDC.getCopyOfContextMap();
            // Sa-Token 1.42+ 使用独立的线程存储，不再依赖 Spring RequestContextHolder。
            var auth = SaTokenContextForThreadLocalStaff.getModelBoxOrNull();
            return () -> {
                var previousRequest = RequestContextHolder.getRequestAttributes();
                var previousContext = MDC.getCopyOfContextMap();
                var previousAuth = SaTokenContextForThreadLocalStaff.getModelBoxOrNull();
                try {
                    if (request == null) RequestContextHolder.resetRequestAttributes();
                    else RequestContextHolder.setRequestAttributes(request);
                    if (context == null) MDC.clear();
                    else MDC.setContextMap(context);
                    if (auth == null) SaTokenContextForThreadLocalStaff.clearModelBox();
                    else SaTokenContextForThreadLocalStaff.setModelBox(
                            auth.getRequest(), auth.getResponse(), auth.getStorage());
                    task.run();
                } finally {
                    if (previousAuth == null) SaTokenContextForThreadLocalStaff.clearModelBox();
                    else SaTokenContextForThreadLocalStaff.setModelBox(
                            previousAuth.getRequest(), previousAuth.getResponse(), previousAuth.getStorage());
                    if (previousRequest == null) RequestContextHolder.resetRequestAttributes();
                    else RequestContextHolder.setRequestAttributes(previousRequest);
                    if (previousContext == null) MDC.clear();
                    else MDC.setContextMap(previousContext);
                }
            };
        };
    }
}
