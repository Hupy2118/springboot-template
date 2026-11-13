package com.devagentstudio.template.engine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "com.cmbchina.managedb.adapter.web.health.HealthController")
@ConditionalOnProperty(prefix = "managed-database", name = "enabled", havingValue = "true")
@ComponentScan("com.cmbchina.managedb")
class ManagedDatabaseIntegrationConfiguration {
}
