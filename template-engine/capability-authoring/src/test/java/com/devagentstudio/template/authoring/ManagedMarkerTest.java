package com.devagentstudio.template.authoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagedMarkerTest {
    @Test
    void derivesARuntimeSafeMarkerFromTheStableIdentity() {
        assertEquals("excel-export-frontend-capability-routes-page-routes",
                ManagedMarker.of("excel-export", "frontend.capability-routes", "page-routes"));
    }

    @Test
    void rejectsIdentityComponentsThatCannotProduceAStableMarker() {
        assertThrows(IllegalArgumentException.class, () -> ManagedMarker.of("Excel", "frontend.capability-routes", "page-routes"));
        assertThrows(IllegalArgumentException.class, () -> ManagedMarker.of("excel-export", "frontend.capability-routes", "page_routes"));
    }
}
