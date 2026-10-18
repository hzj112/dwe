package com.athenahealth.dwe.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables scheduled work and checks that each process runs only one of the API and worker roles. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class RuntimeConfiguration {
    /** Fails startup before background workers start if both roles were accidentally enabled. */
    @Bean
    InitializingBean validateRuntimeRole(Environment environment) {
        return () -> {
            if (environment.acceptsProfiles(Profiles.of("api"))
                    && environment.acceptsProfiles(Profiles.of("worker"))) {
                throw new IllegalStateException("Run api and worker roles in separate processes");
            }
        };
    }
}
