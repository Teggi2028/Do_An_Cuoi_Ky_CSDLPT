package com.distributed.site1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Site1Application {
    public static void main(String[] args) {
        SpringApplication.run(Site1Application.class, args);
        System.out.println("==============================================");
        System.out.println("  Site 1 - Employees  →  http://localhost:8081");
        System.out.println("  Benchmark API: GET /site1/benchmark");
        System.out.println("==============================================");
    }
}
