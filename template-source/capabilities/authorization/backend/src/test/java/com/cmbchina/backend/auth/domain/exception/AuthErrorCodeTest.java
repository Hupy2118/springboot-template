package com.cmbchina.backend.auth.domain.exception;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthErrorCodeTest {

    @Test
    void usesUniquePermissionBusinessErrorCodes() {
        Set<String> codes = Arrays.stream(AuthErrorCode.values())
                .map(AuthErrorCode::getErrorCodeStr)
                .collect(Collectors.toSet());

        assertEquals(AuthErrorCode.values().length, codes.size());
        assertTrue(codes.stream().allMatch(code -> code.matches("^XCD1B[0-9]{2}$")));
    }

    @Test
    void usesSequentialPermissionBusinessErrorCodes() {
        AuthErrorCode[] values = AuthErrorCode.values();
        for (int index = 0; index < values.length; index++) {
            assertEquals(String.format(Locale.ROOT, "XCD1B%02d", index + 1),
                    values[index].getErrorCodeStr());
        }
    }
}
