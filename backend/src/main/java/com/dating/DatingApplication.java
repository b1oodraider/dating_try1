package com.dating;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Auth is token-based (SessionAuthenticationFilter); the default in-memory user with a generated password is not needed.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class DatingApplication {

    public static void main(String[] args) {
        SpringApplication.run(DatingApplication.class, args);
    }
}
