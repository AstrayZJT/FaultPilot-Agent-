package com.astrayzjt.faultpilot.incident.event;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class IncidentEventStreamService {

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<Subscription>> subscribers = new ConcurrentHashMap<>();

    public Subscription register(UUID incidentId, long lastEventId, SseEmitter emitter) {
        Subscription subscription = new Subscription(incidentId, lastEventId, emitter);
        subscribers.computeIfAbsent(incidentId, ignored -> new CopyOnWriteArrayList<>()).add(subscription);
        Runnable remove = () -> remove(subscription);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());
        return subscription;
    }

    @EventListener
    public void publish(IncidentEvent event) {
        for (Subscription subscription : subscribers.getOrDefault(event.incidentId(), new CopyOnWriteArrayList<>())) {
            subscription.send(event);
        }
    }

    public void replay(Subscription subscription, Iterable<IncidentEvent> events) {
        events.forEach(subscription::send);
    }

    private void remove(Subscription subscription) {
        var list = subscribers.get(subscription.incidentId);
        if (list != null) {
            list.remove(subscription);
            if (list.isEmpty()) {
                subscribers.remove(subscription.incidentId, list);
            }
        }
    }

    public static final class Subscription {
        private final UUID incidentId;
        private final SseEmitter emitter;
        private long lastEventId;

        private Subscription(UUID incidentId, long lastEventId, SseEmitter emitter) {
            this.incidentId = incidentId;
            this.lastEventId = lastEventId;
            this.emitter = emitter;
        }

        private synchronized void send(IncidentEvent event) {
            if (event.id() <= lastEventId) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().id(String.valueOf(event.id())).name(event.eventType()).data(event.payload()));
                lastEventId = event.id();
            } catch (IOException exception) {
                emitter.completeWithError(exception);
            }
        }
    }
}
