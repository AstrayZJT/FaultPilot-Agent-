package com.astrayzjt.faultpilot.agent.jvm;

import com.astrayzjt.faultpilot.agent.jvm.config.JvmAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(JvmAgentProperties.class)
public class JvmAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(JvmAgentApplication.class, args);
    }
}
