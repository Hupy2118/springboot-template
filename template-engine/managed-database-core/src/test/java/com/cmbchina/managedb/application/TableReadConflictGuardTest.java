package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.Actor;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ReadOperationSnapshot;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.ResourceStatus;
import com.cmbchina.managedb.domain.TableOperation;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TableReadConflictGuardTest {
    private static final String APP = "b0ddb23acc91";
    private final TableOperationRepository repository = mock(TableOperationRepository.class);
    private final DatabaseSchemaPort schemas = mock(DatabaseSchemaPort.class);
    private final TableReadConflictGuard guard = new TableReadConflictGuard(repository, schemas);

    @BeforeEach
    void namesFollowDatabaseCaseMode() {
        when(schemas.tableKey(eq(2L), anyString())).thenAnswer(call -> call.<String>getArgument(1).toLowerCase(Locale.ROOT));
    }

    @Test
    void runningRebuildAndUnknownDropAffectOnlyTheirExactTables() {
        TableOperation rebuild = operation(101, 2, "Personnel", TableOperation.Mode.REBUILD);
        TableOperation drop = TableOperation.cleanup(101L, APP, 2L, TableOperation.Mode.DROP, Collections.singletonList("Personnel"), Actor.system());
        drop.finish(TableOperation.Status.UNKNOWN, null);
        for (TableOperation active : Arrays.asList(rebuild, drop)) {
            when(repository.readOperationSnapshot(APP, null)).thenReturn(snapshot(101L, 1, 101, active));
            TableReadConflictGuard.Check before = guard.before(APP);
            assertTrue(before.conflicts(Collections.singleton("personnel")));
            assertFalse(before.conflicts(Arrays.asList("department", "personnel_archive")));
        }
    }

    @Test
    void missingForeignMalformedOrReleaseOccupancyBlocksTheEntireResource() {
        TableOperation release = TableOperation.cleanup(101L, APP, 2L, TableOperation.Mode.RELEASE, Collections.emptyList(), Actor.system());
        when(schemas.tableKey(2L, "schema.role")).thenThrow(new BizException(ResourceErrorCode.SQL_UNSUPPORTED));
        for (TableOperation active : Arrays.asList(null, release, operation(101, 2, "schema.role", TableOperation.Mode.CREATE), operation(101, 3, "role", TableOperation.Mode.CREATE),
                operation(102, 2, "role", TableOperation.Mode.CREATE), operation(101, 2, "", TableOperation.Mode.CREATE))) {
            when(repository.readOperationSnapshot(APP, null)).thenReturn(snapshot(101L, 1, 101, active));
            assertTrue(guard.before(APP).conflicts(Collections.emptyList()));
        }
    }

    @Test
    void uncertainHistoryBlocksEvenAnUnrelatedRead() {
        TableOperation first = finished(101, "role");
        TableOperation second = finished(102, "resource");
        when(repository.readOperationSnapshot(APP, null)).thenReturn(snapshot(null, 1, 100, null));
        TableReadConflictGuard.Check before = guard.before(APP);

        for (ReadOperationSnapshot after : Arrays.asList(
                snapshot(null, 0, 0, null),                         // count went backwards
                snapshot(null, 2, 100, null),                       // newly accepted ID is below the marker
                snapshot(null, 102, 102, null, first, second),      // truncated scan
                snapshot(null, 3, 102, null, second, first),        // invalid order
                snapshot(null, 3, 102, null, first, first),         // duplicate row
                snapshot(null, 2, 102, null, first),                 // high-water mark is missing
                snapshot(null, 2, 101, null, (TableOperation) null)  // unreadable retained record
        )) {
            when(repository.readOperationSnapshot(APP, 100L)).thenReturn(after);
            assertTrue(guard.after(before).conflicts(Collections.singleton("personnel")));
        }
    }

    @Test
    void retainsBeforeTargetsAndCombinesNewActiveAndCompletedOperations() {
        TableOperation old = operation(100, 2, "role", TableOperation.Mode.CREATE);
        TableOperation next = operation(102, 2, "resource", TableOperation.Mode.CREATE);
        TableOperation done = finished(101, "role_member");
        when(repository.readOperationSnapshot(APP, null)).thenReturn(snapshot(100L, 1, 100, old));
        when(repository.readOperationSnapshot(APP, 100L)).thenReturn(snapshot(102L, 3, 102, next, done, next));
        TableReadConflictGuard.Check after = guard.after(guard.before(APP));
        assertFalse(after.conflicts(Collections.singleton("personnel")));
        for (String target : Arrays.asList("role", "resource", "role_member")) {
            assertTrue(after.conflicts(Collections.singleton(target)));
        }
    }

    private TableOperation operation(long id, long datasourceId, String name, TableOperation.Mode mode) {
        return TableOperation.start(id, APP, datasourceId, name, "CREATE TABLE", mode, false, Actor.system());
    }

    private TableOperation finished(long id, String name) {
        TableOperation operation = operation(id, 2, name, TableOperation.Mode.CREATE);
        operation.finish(TableOperation.Status.FAILED, null);
        return operation;
    }

    private ReadOperationSnapshot snapshot(Long activeId, long count, long maxId, TableOperation active, TableOperation... delta) {
        DatasourceResource resource = DatasourceResource.restore(new DatasourceKey(APP), 2L, null, null, null, null,
                ResourceStatus.REGISTERED, false, activeId);
        return new ReadOperationSnapshot(resource, count, maxId, active, Arrays.asList(delta));
    }
}
