package com.wk.flashsale.bench;

import com.wk.flashsale.booking.ReservationRepository;
import com.wk.flashsale.booking.ReservationStatus;
import com.wk.flashsale.booking.strategy.BookingResult;
import com.wk.flashsale.booking.strategy.BookingStrategy;
import com.wk.flashsale.event.EventRepository;
import com.wk.flashsale.queue.WaitingRoom;
import com.wk.flashsale.stock.StockGate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Étape 3 : expose chaque stratégie en HTTP pour les tests de charge Gatling.
 * Uniquement avec le profil "bench" : la remise à zéro n'a rien à faire en production.
 */
@RestController
@RequestMapping("/bench/events/{eventId}")
@Profile("bench")
class BenchmarkController {

    // Clés = noms des beans : naive, optimistic, pessimistic, atomic, check, redis (si activé)
    private final Map<String, BookingStrategy> strategies;
    private final StockGate stockGate;
    private final Optional<WaitingRoom> waitingRoom;
    private final ReservationRepository reservationRepository;
    private final EventRepository eventRepository;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    BenchmarkController(Map<String, BookingStrategy> strategies, StockGate stockGate, Optional<WaitingRoom> waitingRoom,
                        ReservationRepository reservationRepository, EventRepository eventRepository,
                        JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.strategies = strategies;
        this.stockGate = stockGate;
        this.waitingRoom = waitingRoom;
        this.reservationRepository = reservationRepository;
        this.eventRepository = eventRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
    }

    record BookingResponse(String strategy, BookingResult result) {
    }

    @PostMapping("/bookings")
    ResponseEntity<?> book(@PathVariable long eventId, @RequestParam String strategy, @RequestParam String userId) {
        BookingStrategy selected = strategies.get(strategy);
        if (selected == null) {
            return ResponseEntity.badRequest().body("Stratégie inconnue. Disponibles : " + strategies.keySet());
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        BookingResult result = selected.book(eventId, userId);
        sample.stop(meterRegistry.timer("flashsale.booking", "strategy", strategy, "result", result.name()));

        HttpStatus status = switch (result) {
            case SUCCESS -> HttpStatus.CREATED;
            case SOLD_OUT -> HttpStatus.CONFLICT;
            // Abandon après trop de conflits : "réessayez plus tard"
            case CONFLICT -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(new BookingResponse(strategy, result));
    }

    @PostMapping("/reset")
    ResponseEntity<Void> reset(@PathVariable long eventId, @RequestParam(defaultValue = "500") int seats) {
        jdbcTemplate.update("DELETE FROM reservation WHERE event_id = ?", eventId);
        jdbcTemplate.update("UPDATE event SET total_seats = ?, available_seats = ? WHERE id = ?", seats, seats, eventId);
        stockGate.reset(eventId);
        waitingRoom.ifPresent(room -> room.reset(eventId));
        return ResponseEntity.noContent().build();
    }

    record CheckResponse(int totalSeats, int availableSeats, long reservations, long oversold, boolean consistent) {
    }

    /** Lu par Gatling à la fin du tir : c'est la base qui fait foi, pas les réponses HTTP. */
    @GetMapping("/check")
    ResponseEntity<CheckResponse> check(@PathVariable long eventId) {
        return ResponseEntity.of(eventRepository.findById(eventId).map(event -> {
            long active = reservationRepository.countByEventId(eventId)
                    - reservationRepository.countByEventIdAndStatus(eventId, ReservationStatus.EXPIRED);
            return new CheckResponse(event.getTotalSeats(), event.getAvailableSeats(), active,
                    Math.max(0, active - event.getTotalSeats()),
                    event.getAvailableSeats() == event.getTotalSeats() - active);
        }));
    }
}
