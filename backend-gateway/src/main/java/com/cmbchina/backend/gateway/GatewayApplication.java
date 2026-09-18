package com.cmbchina.backend.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 启动独立于业务 Backend 进程的公开 Gateway 服务。 */
@SpringBootApplication
public class GatewayApplication {
    /** 启动生成应用的独立 Gateway。 */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
