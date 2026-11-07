package com.cmbchina.backend.auth.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InitialAdministratorsTest {
    private List<AuthorizationBootstrapCommand.AdminMember> parse(Object... values) {
        return AuthorizationBootstrapCommand.parseAdministrators(new ObjectMapper().valueToTree(values));
    }

    @Test void savedInputVectorsPreserveLegacyAndSplitOnlyValidComposites() {
        for (String value : Arrays.asList("00123456/张三", "00000001/欧阳娜娜", "00000000/阿·甲 乙")) {
            AuthorizationBootstrapCommand.AdminMember member = parse(value).get(0);
            assertEquals(value.substring(0, 8), member.memberId);
            assertEquals(value.substring(9), member.memberName);
            assertFalse(member.legacy);
        }
        for (String value : Arrays.asList("00123456", "ops@example.com", "1234567/张三", "123456789/张三",
                "00123456/", "00123456/张三/别名", "00123456／张三", "00123456/ 张三",
                "00123456/张,三", "00123456/张，三", "00123456/张\n三", "00123456/张\u2028三")) {
            AuthorizationBootstrapCommand.AdminMember member = parse(value).get(0);
            assertEquals(value, member.memberId);
            assertEquals(value, member.memberName);
            assertTrue(member.legacy);
        }
        AuthorizationBootstrapCommand.AdminMember trimmed = parse("\u00a0 00123456/张三\u3000").get(0);
        assertEquals("00123456", trimmed.memberId);
        assertEquals("张三", trimmed.memberName);
    }

    @Test void unicodeLengthsCountsConflictsAndErrorsAreSafe() {
        String supplementary = new String(Character.toChars(0x20000));
        String name = String.join("", Collections.nCopies(32, supplementary));
        assertEquals(name, parse("00123456/" + name).get(0).memberName);
        assertThrows(RuntimeException.class, () -> parse("00123456/" + name + supplementary));
        assertThrows(RuntimeException.class, () -> parse(String.join("", Collections.nCopies(33, "名"))));
        assertEquals(1, parse("00123456/张三", "00123456/张三").size());
        assertEquals(1, parse("ops@example.com", "ops@example.com").size());
        for (Object[] values : Arrays.asList(new Object[]{"00123456/张三", "00123456/李四"},
                new Object[]{"00123456", "00123456/00123456"}, new Object[]{"00123456", "00123456/张三"},
                new Object[]{"current-user"}, new Object[]{" CURRENT-USER "}, new Object[]{"\u3000"},
                new Object[]{1}, new Object[]{null}, new Object[]{})) {
            assertThrows(RuntimeException.class, () -> parse(values));
        }
        assertEquals(1, parse(Collections.nCopies(100, "00123456/张三").toArray()).size());
        assertThrows(RuntimeException.class, () -> parse(Collections.nCopies(101, "00123456/张三").toArray()));
        RuntimeException error = assertThrows(RuntimeException.class, () -> parse("00123456/张三", "00123456/李四"));
        assertFalse(error.getMessage().contains("00123456"));
        assertFalse(error.getMessage().contains("李四"));
    }

    @Test void jdbcBindsSeparateColumnsAndSkipsExistingMemberWithoutAnyMutation() throws Exception {
        List<List<String>> inserts = new ArrayList<>();
        Map<String, String> stored = new HashMap<>();
        stored.put("00123456", "00123456"); // Existing legacy name must survive the new configuration.
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")) {
                        String sql = (String) args[0];
                        assertTrue(sql.equals("SELECT 1 FROM role_member WHERE role_id = ? AND member_id = ?")
                                || sql.equals("INSERT INTO role_member (role_id, member_id, member_name, created_by) VALUES (?, ?, ?, 'SYSTEM')"), sql);
                        Map<Integer, String> bindings = new HashMap<>();
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{PreparedStatement.class}, (p, m, a) -> {
                            if (m.getName().equals("setString")) { bindings.put((Integer) a[0], (String) a[1]); return null; }
                            if (m.getName().equals("executeQuery")) {
                                assertEquals("SYSTEM_ADMIN", bindings.get(1));
                                boolean exists = stored.containsKey(bindings.get(2));
                                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ResultSet.class},
                                        (r, rm, ra) -> rm.getName().equals("next") ? exists : null);
                            }
                            if (m.getName().equals("executeUpdate")) {
                                inserts.add(Arrays.asList(bindings.get(1), bindings.get(2), bindings.get(3)));
                                stored.put(bindings.get(2), bindings.get(3)); return 1;
                            }
                            return null;
                        });
                    }
                    return null;
                });
        Class<?> inputType = Class.forName(AuthorizationBootstrapCommand.class.getName() + "$BootstrapInput");
        Constructor<?> inputConstructor = inputType.getDeclaredConstructor(List.class, List.class, String.class); inputConstructor.setAccessible(true);
        Object input = inputConstructor.newInstance(Collections.emptyList(), parse("00123456/张三", "00000001/李四", "ops@example.com"), "test");
        Class<?> executorType = Class.forName(AuthorizationBootstrapCommand.class.getName() + "$BootstrapExecutor");
        Constructor<?> executorConstructor = executorType.getDeclaredConstructor(Connection.class, java.nio.file.Path.class, inputType); executorConstructor.setAccessible(true);
        Object executor = executorConstructor.newInstance(connection, null, input);
        Method ensure = executorType.getDeclaredMethod("ensureInitialAdministrators"); ensure.setAccessible(true);
        assertEquals(2, ensure.invoke(executor));
        assertEquals(Arrays.asList(Arrays.asList("SYSTEM_ADMIN", "00000001", "李四"),
                Arrays.asList("SYSTEM_ADMIN", "ops@example.com", "ops@example.com")), inserts);
        assertEquals("00123456", stored.get("00123456"));
        assertEquals(0, ensure.invoke(executor));
        assertEquals(2, inserts.size());
    }
}
