package com.stellarink.serviceapi.feign;

import com.stellarink.serviceapi.feign.fallback.PostServiceClientFallbackFactory;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.post.PostSummaryVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * 文章服务客户端：stats-service 等调用方通过服务发现负载均衡访问内部汇总接口
 */
@FeignClient(name = "post-service", contextId = "postSummaryClient",
        path = "/internal/posts", fallbackFactory = PostServiceClientFallbackFactory.class)
public interface PostServiceClient {

    @GetMapping("/summary")
    Response<List<PostSummaryVO>> summary();
}
