package com.devagentstudio.template.engine;

import com.devagentstudio.template.engine.service.TemplateEngineProperties;
import com.devagentstudio.template.engine.service.appupdate.AppUpdateProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * 引擎服务统一启动入口。
 * 通过 @ComponentScan 覆盖 engine-service 和 managed-database-core 的所有包，
 * 使 Controller、Configuration、Filter 等组件均能被 Spring 容器扫描注册。
 */
@SpringBootApplication
@EnableConfigurationProperties({ TemplateEngineProperties.class, AppUpdateProperties.class })
@ComponentScan(basePackages = {
        "com.devagentstudio.template",
        "com.cmbchina.managedb"
})
public class EngineServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(EngineServiceApplication.class, args);
    }
}
