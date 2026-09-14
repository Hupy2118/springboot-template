package com.xcodeagent.template.authoring;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CapabilityStatusReportTest {
    @Test void reportsReadyCountsForSupportedChanges() {
        CapabilityDraft draft = new CapabilityDraft("demo",
                Collections.singletonList(new AdditionDraft("demo.file", "file.ts", "file.ts")),
                java.util.Arrays.asList(new StrategyDraft("ENSURE_IMPORT", "frontend.surface", null, Collections.<String, Object>singletonMap("importStatement", "import Demo from 'demo';")),
                        new StrategyDraft("TEXT_ANCHOR_INSERT", "frontend.surface", "slot", Collections.<String, Object>emptyMap())),
                Collections.<UnsupportedChange>emptyList());
        CapabilityStatusReport report = new CapabilityStatusReport(draft);
        assertEquals(CapabilityStatusReport.Status.READY, report.status());
        assertEquals(1, report.additions().size());
        assertEquals(1, report.importCount());
        assertEquals(1, report.anchorInsertCount());
    }

    @Test void reportsBlockedWithoutDiscardingUnsupportedDetail() {
        CapabilityDraft draft = new CapabilityDraft("demo", Collections.<AdditionDraft>emptyList(), Collections.<StrategyDraft>emptyList(),
                Collections.singletonList(new UnsupportedChange("frontend/shared.ts", "MODIFY_NON_SURFACE")));
        CapabilityStatusReport report = new CapabilityStatusReport(draft);
        assertEquals(CapabilityStatusReport.Status.BLOCKED, report.status());
        assertEquals("frontend/shared.ts", report.unsupportedChanges().get(0).path());
        assertEquals("MODIFY_NON_SURFACE", report.unsupportedChanges().get(0).reason());
    }
}
