package com.stellarink.link;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.stellarink")
@MapperScan("com.stellarink.link.mapper")
public class LinkServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkServiceApplication.class, args);
    }
}
