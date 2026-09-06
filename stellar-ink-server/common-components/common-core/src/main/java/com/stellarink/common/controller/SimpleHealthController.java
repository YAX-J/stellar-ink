package com.stellarink.common.controller;

import com.stellarink.sharedmodel.response.Response;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 简单健康检查：供探活与联调使用
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SimpleHealthController {

    @GetMapping("/health")
    public Response<String> health() {
        return Response.success("UP");
    }
}
