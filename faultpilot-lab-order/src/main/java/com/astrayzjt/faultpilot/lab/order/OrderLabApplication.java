package com.astrayzjt.faultpilot.lab.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OrderLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderLabApplication.class, args);
    }
}
