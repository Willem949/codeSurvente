package com.wk.flashsale.event;

/**
 * DTO exposé par l'API : on ne renvoie jamais l'entité JPA directement.
 */
public record EventResponse(Long id, String name, int totalSeats, int availableSeats) {

    static EventResponse from(Event event) {
        return new EventResponse(event.getId(), event.getName(), event.getTotalSeats(), event.getAvailableSeats());
    }
}
