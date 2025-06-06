package io.camunda;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;


@SpringBootApplication
@ConfigurationPropertiesScan("io.camunda")

public class Application {

    public static void main(String[] args) {

        SpringApplication.run(Application.class, args);
        // thanks to Spring, the class CherryJobRunnerFactory is active. All runners (worker,
        // connectors) start then
    }
}
