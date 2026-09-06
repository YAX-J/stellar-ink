package com.stellarink.meteor;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.stellarink.meteor", "com.stellarink.common"})
@EnableDiscoveryClient
@MapperScan("com.stellarink.meteor.**.mapper")
public class MeteorServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeteorServiceApplication.class, args);
    }
}
