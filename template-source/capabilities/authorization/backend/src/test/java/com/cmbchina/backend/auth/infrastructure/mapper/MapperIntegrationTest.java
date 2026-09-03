package com.cmbchina.backend.auth.infrastructure.mapper;

import com.cmbchina.backend.auth.infrastructure.mapper.ResourceMapper;
import com.cmbchina.backend.auth.infrastructure.mapper.RoleMapper;
import com.cmbchina.backend.auth.infrastructure.mapper.RoleMemberMapper;
import com.cmbchina.backend.auth.infrastructure.mapper.RoleResourceMapper;
import com.cmbchina.backend.auth.infrastructure.po.ResourcePO;
import com.cmbchina.backend.auth.infrastructure.po.RoleMemberPO;
import com.cmbchina.backend.auth.infrastructure.po.RolePO;
import com.cmbchina.backend.auth.infrastructure.po.RoleResourcePO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = "xcodeagent.authorization.bootstrap-on-startup=false")
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_INTEGRATION", matches = "true")
class MapperIntegrationTest {

    @Autowired
    private RoleMapper roleMapper;
    @Autowired
    private ResourceMapper resourceMapper;
    @Autowired
    private RoleResourceMapper roleResourceMapper;
    @Autowired
    private RoleMemberMapper roleMemberMapper;

    @Test
    void fourTableJoinReturnsUnionForMemberWithMultipleRoles() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String roleA = "T" + suffix + "A";
        String roleB = "T" + suffix + "B";
        String member = "M" + suffix;
        String resourceA = "test_page_" + suffix.toLowerCase();
        String resourceB = "test_operation_" + suffix.toLowerCase();

        resourceMapper.insert(resource(resourceA, "page"));
        resourceMapper.insert(resource(resourceB, "operation"));
        roleMapper.insert(role(roleA));
        roleMapper.insert(role(roleB));
        roleResourceMapper.insertBatch(Arrays.asList(
                new RoleResourcePO(roleA, resourceA, null, "TEST"),
                new RoleResourcePO(roleB, resourceB, null, "TEST")));
        roleMemberMapper.insertBatch(Arrays.asList(
                new RoleMemberPO(roleA, member, "测试成员", null, "TEST"),
                new RoleMemberPO(roleB, member, "测试成员", null, "TEST")));

        assertEquals(Arrays.asList(resourceB, resourceA).stream().sorted().collect(java.util.stream.Collectors.toList()),
                roleResourceMapper.findEffectiveResourceKeys(member));
        assertEquals(2, roleMemberMapper.findByMemberId(member).size());
    }

    private RolePO role(String roleId) {
        RolePO role = new RolePO();
        role.setRoleId(roleId);
        role.setRoleName("测试角色");
        role.setEnable(true);
        role.setIsSystem(false);
        role.setIsDeleted(false);
        role.setCreatedBy("TEST");
        return role;
    }

    private ResourcePO resource(String key, String type) {
        ResourcePO resource = new ResourcePO();
        resource.setKey(key);
        resource.setName("测试资源");
        resource.setOrigin("business");
        resource.setType(type);
        return resource;
    }
}


