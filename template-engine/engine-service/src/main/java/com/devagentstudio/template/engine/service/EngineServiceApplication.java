package com.devagentstudio.template.engine.service;

import com.devagentstudio.template.engine.service.appupdate.AppUpdateProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({TemplateEngineProperties.class, AppUpdateProperties.class})
public class EngineServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(EngineServiceApplication.class, args);
    }
}
