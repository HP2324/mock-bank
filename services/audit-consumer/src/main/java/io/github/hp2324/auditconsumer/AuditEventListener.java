package io.github.hp2324.auditconsumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class AuditEventListener {
    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);

    private final AuditEventParser parser;
    private final ConsumedEventStore store;

    public AuditEventListener(AuditEventParser parser, ConsumedEventStore store) {
        this.parser = parser;
        this.store = store;
    }

    @KafkaListener(topics = "${auditconsumer.topic}")
    public void onAuditEvent(ConsumerRecord<String, String> record) {
        AuditEventMessage event = parser.parse(record.value());
        boolean stored = store.save(event, record.partition(), record.offset());
        if (stored) {
            log.info("Stored audit event {} {} (partition {}, offset {})",
                    event.type(), event.eventId(), record.partition(), record.offset());
        } else {
            log.info("Skipped duplicate audit event {}", event.eventId());
        }
    }
}
