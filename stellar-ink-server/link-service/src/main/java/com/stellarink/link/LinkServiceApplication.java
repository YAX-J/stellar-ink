package com.stellarink.link;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.stellarink.link", "com.stellarink.common"})
@EnableDiscoveryClient
@MapperScan("com.stellarink.link.**.mapper")
public class LinkServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkServiceApplication.class, args);
    }
}
