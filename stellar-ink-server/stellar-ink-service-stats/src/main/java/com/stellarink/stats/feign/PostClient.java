package com.stellarink.stats.feign;

import com.stellarink.common.result.Result;
import com.stellarink.stats.feign.dto.PostSummary;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * 文章服务客户端：走 Nacos 服务发现负载均衡，调用内部汇总接口
 */
@FeignClient(name = "post-service", contextId = "postSummaryClient", path = "/internal/posts")
public interface PostClient {

    @GetMapping("/summary")
    Result<List<PostSummary>> summary();
}
