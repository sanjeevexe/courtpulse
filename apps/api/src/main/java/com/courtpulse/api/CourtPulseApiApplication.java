package com.courtpulse.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CourtPulseApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(CourtPulseApiApplication.class, args);
    }
}
