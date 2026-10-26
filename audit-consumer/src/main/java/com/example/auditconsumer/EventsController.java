package com.example.auditconsumer;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventsController {
    private final ConsumedEventStore store;

    public EventsController(ConsumedEventStore store) {
        this.store = store;
    }

    @GetMapping
    public List<ConsumedEventStore.StoredEvent> recent(@RequestParam(defaultValue = "50") int limit) {
        return store.recent(Math.max(1, Math.min(limit, 500)));
    }

    @GetMapping("/summary")
    public List<ConsumedEventStore.TypeCount> summary() {
        return store.countsByType();
    }
}
