package com.astrayzjt.faultpilot.incident.api;

import com.astrayzjt.faultpilot.incident.event.IncidentEventService;
import com.astrayzjt.faultpilot.incident.event.IncidentEventStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/incidents/{incidentId}/events")
public class IncidentEventController {

    private final IncidentEventService eventService;
    private final IncidentEventStreamService streamService;

    public IncidentEventController(IncidentEventService eventService, IncidentEventStreamService streamService) {
        this.eventService = eventService;
        this.streamService = streamService;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID incidentId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        long after = parse(lastEventId);
        SseEmitter emitter = new SseEmitter(0L);
        var subscription = streamService.register(incidentId, after, emitter);
        streamService.replay(subscription, eventService.findAfter(incidentId, after));
        return emitter;
    }

    private long parse(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
