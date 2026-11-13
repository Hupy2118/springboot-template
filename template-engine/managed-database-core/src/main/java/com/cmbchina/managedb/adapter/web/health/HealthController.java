package com.cmbchina.managedb.adapter.web.health;

import com.cmb.bee.web.annotations.BeeResponseBody;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基础设施健康入口，只表示框架进程可用，不代表数据库资源已就绪。
 */
@RestController
@BeeResponseBody
@RequiredArgsConstructor
public class HealthController {

    private final Environment environment;

    /**
     * 返回进程状态和当前环境，不触发数据库连接或资源分配。路径前缀 /managed-database 与 ServiceKeyFilter 匹配。
     */
    @GetMapping("/managed-database/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "managed-database-service");
        body.put("status", "UP");
        String[] profiles = environment.getActiveProfiles();
        body.put("environment", String.join(",", profiles.length == 0 ? environment.getDefaultProfiles() : profiles));
        return body;
    }
}
