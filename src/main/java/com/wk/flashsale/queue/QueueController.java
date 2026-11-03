package com.wk.flashsale.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events/{eventId}/queue")
@ConditionalOnProperty(name = {"flashsale.redis.enabled", "flashsale.queue.enabled"}, havingValue = "true")
class QueueController {

    private final WaitingRoom waitingRoom;

    QueueController(WaitingRoom waitingRoom) {
        this.waitingRoom = waitingRoom;
    }

    /** Entrer dans la file : renvoie un ticket et sa position. */
    @PostMapping
    ResponseEntity<QueueTicket> join(@PathVariable long eventId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(waitingRoom.join(eventId));
    }

    /** À interroger régulièrement jusqu'à ADMITTED. 404 si le ticket est inconnu, expiré ou déjà utilisé. */
    @GetMapping("/{token}")
    ResponseEntity<QueueTicket> status(@PathVariable long eventId, @PathVariable String token) {
        return ResponseEntity.of(waitingRoom.status(eventId, token));
    }
}
