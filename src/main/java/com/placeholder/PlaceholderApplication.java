package com.placeholder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PlaceholderApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlaceholderApplication.class, args);
    }
}