package com.stellarink.serviceapi.feign.fallback;

import com.stellarink.serviceapi.feign.PostServiceClient;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.post.PostSummaryVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文章服务降级工厂：post-service 不可用时返回服务不可用，避免调用方 500
 */
@Slf4j
@Component
public class PostServiceClientFallbackFactory implements PostServiceClient {

    @Override
    public Response<List<PostSummaryVO>> summary() {
        log.warn("post-service 不可用，summary 接口降级");
        return Response.error(ErrorCode.SERVICE_UNAVAILABLE, "文章服务暂时没有回应");
    }
}
