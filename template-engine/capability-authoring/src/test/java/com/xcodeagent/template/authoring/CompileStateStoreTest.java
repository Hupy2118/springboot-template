package com.xcodeagent.template.authoring;

import com.xcodeagent.template.engine.source.TemplateSourceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompileStateStoreTest {
    @TempDir Path temporaryDirectory;
    @Test void roundTripsOnlyTheDraftOwnershipIdentity() {
        CompileState state = new CompileState("demo", Arrays.asList("demo.b", "demo.a"), Arrays.asList("demo.postcondition"));
        CompileStateStore store = new CompileStateStore(); store.save(temporaryDirectory, state);
        CompileState loaded = store.load(temporaryDirectory);
        assertEquals("demo", loaded.capabilityId());
        assertEquals(Arrays.asList("demo.a", "demo.b"), loaded.strategyIds());
        assertEquals(Arrays.asList("demo.postcondition"), loaded.validatorIds());
    }
    @Test void rejectsMalformedOwnershipState() throws Exception {
        Files.write(temporaryDirectory.resolve("compile-state.yaml"), "capabilityId: Demo\nstrategyIds: not-a-list\n".getBytes("UTF-8"));
        assertThrows(TemplateSourceException.class, () -> new CompileStateStore().load(temporaryDirectory));
    }
}
