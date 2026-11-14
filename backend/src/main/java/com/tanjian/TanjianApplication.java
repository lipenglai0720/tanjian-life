package com.tanjian;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.tanjian.mapper")
@SpringBootApplication
@EnableAspectJAutoProxy(exposeProxy = true)
@EnableScheduling
public class TanjianApplication {

    public static void main(String[] args) {
        SpringApplication.run(TanjianApplication.class, args);
    }

}
