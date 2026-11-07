package com.stellarink.ai.config;

import cn.dev33.satoken.context.SaTokenContextForThreadLocalStaff;
import cn.dev33.satoken.servlet.model.SaRequestForServlet;
import cn.dev33.satoken.servlet.model.SaResponseForServlet;
import cn.dev33.satoken.servlet.model.SaStorageForServlet;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class QaStreamExecutionConfigTest {

    @Test
    void propagatesRequestAndTraceWithoutLeakingIntoNextTask() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "test-request-only");
        var attributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attributes);
        MDC.put("traceId", "stream-trace");
        SaTokenContextForThreadLocalStaff.setModelBox(new SaRequestForServlet(request),
                new SaResponseForServlet(new MockHttpServletResponse()), new SaStorageForServlet(request));
        Runnable decorated;
        try {
            decorated = QaStreamExecutionConfig.requestContextDecorator().decorate(() -> {
                assertSame(attributes, RequestContextHolder.getRequestAttributes());
                assertEquals("stream-trace", MDC.get("traceId"));
                assertEquals("test-request-only", SaTokenContextForThreadLocalStaff.getRequest()
                        .getHeader("Authorization"), "签名所用身份必须进入工作线程");
                assertEquals("test-request-only", ((ServletRequestAttributes)
                        RequestContextHolder.currentRequestAttributes()).getRequest().getHeader("Authorization"));
            });
        } finally {
            RequestContextHolder.resetRequestAttributes();
            MDC.clear();
            SaTokenContextForThreadLocalStaff.clearModelBox();
        }
        var worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                MDC.put("worker", "existing");
                try {
                    decorated.run();
                    assertNull(RequestContextHolder.getRequestAttributes(), "任务完成后不得串用上个用户的请求");
                    assertNull(MDC.get("traceId"), "任务完成后不得串用上条链路的 traceId");
                    assertNull(SaTokenContextForThreadLocalStaff.getModelBoxOrNull(), "Sa-Token 身份不得串到下一请求");
                    assertEquals("existing", MDC.get("worker"));
                } finally {
                    MDC.clear();
                    SaTokenContextForThreadLocalStaff.clearModelBox();
                }
            }).get(3, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
    }
}
