package dev.rehearsal;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BaselineStoreTests {
    @Test void activeReservationsCountAgainstTheProjectQuota() {
        var jdbc=mock(JdbcTemplate.class);
        var projects=mock(ProjectService.class);
        var store=new BaselineStore(jdbc,projects);
        when(jdbc.queryForObject(contains("b.published or r.status in ('QUEUED','RUNNING')"),
            eq(Integer.class),eq(1L))).thenReturn(5);

        var error=assertThrows(ResponseStatusException.class,() -> store.reserve(1,10,
            new BaselineStore.NewSnapshot("Sixth snapshot","sr_baseline_00000000000000000000000000000001")));
        assertEquals(409,error.getStatusCode().value());
        verify(jdbc).queryForObject(contains("select count(*)"),eq(Integer.class),eq(1L));
        verifyNoMoreInteractions(jdbc,projects);
    }
}
