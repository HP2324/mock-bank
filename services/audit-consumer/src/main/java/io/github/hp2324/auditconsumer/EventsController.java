package io.github.hp2324.auditconsumer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Consumed events", description = "Audit events stored from Kafka")
@RestController
@RequestMapping("/api/events")
public class EventsController {
    private final ConsumedEventStore store;

    public EventsController(ConsumedEventStore store) {
        this.store = store;
    }

    @Operation(summary = "Newest consumed events", description = "Includes each event's Kafka partition and offset; limit is clamped to 1–500.")
    @GetMapping
    public List<ConsumedEventStore.StoredEvent> recent(@RequestParam(defaultValue = "50") int limit) {
        return store.recent(Math.max(1, Math.min(limit, 500)));
    }

    @Operation(summary = "Event counts per type")
    @GetMapping("/summary")
    public List<ConsumedEventStore.TypeCount> summary() {
        return store.countsByType();
    }
}
