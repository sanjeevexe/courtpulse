package com.courtpulse.durablereplay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class DurableReplayApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(DurableReplayApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext context = application.run(args);
        context.close();
    }
}
