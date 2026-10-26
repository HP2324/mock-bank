package io.github.hp2324.auditconsumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventsController.class)
class EventsControllerTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ConsumedEventStore store;

    @Test
    void summaryListsCountsPerEventType() throws Exception {
        when(store.countsByType()).thenReturn(List.of(
                new ConsumedEventStore.TypeCount("SUBACCOUNT_OPENED", 3, Instant.parse("2026-10-26T14:30:15Z"))));

        mvc.perform(get("/api/events/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("SUBACCOUNT_OPENED"))
                .andExpect(jsonPath("$[0].count").value(3));
    }

    @Test
    void recentEventsLimitIsClamped() throws Exception {
        mvc.perform(get("/api/events").param("limit", "0")).andExpect(status().isOk());

        verify(store).recent(1);
    }
}
