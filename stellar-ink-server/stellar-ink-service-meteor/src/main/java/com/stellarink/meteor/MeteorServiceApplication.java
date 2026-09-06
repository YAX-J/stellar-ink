package com.stellarink.meteor;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.stellarink")
@MapperScan("com.stellarink.meteor.mapper")
public class MeteorServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeteorServiceApplication.class, args);
    }
}
