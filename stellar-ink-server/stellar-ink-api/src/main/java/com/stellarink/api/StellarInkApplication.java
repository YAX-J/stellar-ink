package com.stellarink.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.stellarink")
@MapperScan("com.stellarink.dao.mapper")
public class StellarInkApplication {

    public static void main(String[] args) {
        SpringApplication.run(StellarInkApplication.class, args);
    }
}
