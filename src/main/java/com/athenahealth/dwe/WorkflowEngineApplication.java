package com.athenahealth.dwe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.athenahealth.dwe.config.EngineProperties;

/** Starts Spring Boot; the selected profile runs either the API/coordinator or the worker role. */
@SpringBootApplication
@EnableConfigurationProperties(EngineProperties.class)
public class WorkflowEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkflowEngineApplication.class, args);
    }
}
