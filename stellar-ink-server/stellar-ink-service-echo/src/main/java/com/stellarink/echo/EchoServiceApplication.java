package com.stellarink.echo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.stellarink")
@MapperScan("com.stellarink.echo.mapper")
public class EchoServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EchoServiceApplication.class, args);
    }
}
