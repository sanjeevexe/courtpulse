package com.courtpulse.queuereplay;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class QueueReplayApplication {
    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--help")) {
            QueueReplayCommand.printUsage();
            return;
        }
        SpringApplication application = new SpringApplication(QueueReplayApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext context = application.run(args);
        context.close();
    }
}
