package com.cayleywcs;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.cayleywcs.**.mapper")
public class CayleyWcsApplication {

    public static void main(String[] args) {
        SpringApplication.run(CayleyWcsApplication.class, args);
    }
}
