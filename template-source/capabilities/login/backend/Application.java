package com.cmbchina.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.cmb.bee.auth.client.config.EnableAuthClient;
/**
 * spring启动类
 */
@EnableAuthClient
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}