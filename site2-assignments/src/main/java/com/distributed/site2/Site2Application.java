package com.distributed.site2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Site2Application {
    public static void main(String[] args) {
        SpringApplication.run(Site2Application.class, args);
        System.out.println("==============================================");
        System.out.println("  Site 2 - Assignments  →  http://localhost:8082");
        System.out.println("==============================================");
    }
}
