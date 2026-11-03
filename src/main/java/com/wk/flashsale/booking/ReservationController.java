package com.wk.flashsale.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * API d'achat :
 * <ol>
 *   <li>{@code POST /events/{id}/reservations} bloque une place (header {@code Idempotency-Key} obligatoire)</li>
 *   <li>{@code POST /reservations/{id}/confirm} la confirme après paiement, avant expiration</li>
 * </ol>
 */
@RestController
class ReservationController {

    private final ReservationService reservationService;

    ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    record HoldRequest(@NotBlank @Size(max = 100) String userId) {
    }

    @PostMapping("/events/{eventId}/reservations")
    ResponseEntity<?> hold(@PathVariable long eventId,
                           @RequestHeader("Idempotency-Key") @Size(max = 100) String idempotencyKey,
                           @RequestHeader(name = "X-Queue-Token", required = false) String queueToken,
                           @Valid @RequestBody HoldRequest request) {
        HoldOutcome outcome = reservationService.hold(eventId, request.userId(), idempotencyKey, queueToken);
        return switch (outcome.status()) {
            case CREATED -> ResponseEntity.status(HttpStatus.CREATED).body(ReservationResponse.from(outcome.reservation()));
            // Même réponse qu'au premier appel, en 200 : le client ne voit pas la différence, c'est le but
            case REPLAYED -> ResponseEntity.ok(ReservationResponse.from(outcome.reservation()));
            case SOLD_OUT -> problem(HttpStatus.CONFLICT, "Plus aucune place disponible");
            case ALREADY_RESERVED -> problem(HttpStatus.CONFLICT, "Vous avez déjà une réservation pour cet événement");
            case NOT_ADMITTED -> problem(HttpStatus.FORBIDDEN, "Ticket de file d'attente absent, pas encore admis ou déjà utilisé");
            case KEY_REUSED -> problem(HttpStatus.UNPROCESSABLE_CONTENT, "Cette Idempotency-Key a déjà servi pour une autre demande");
        };
    }

    @PostMapping("/reservations/{reservationId}/confirm")
    ResponseEntity<?> confirm(@PathVariable long reservationId) {
        return switch (reservationService.confirm(reservationId)) {
            case CONFIRMED -> ResponseEntity.noContent().build();
            // 410 Gone : la ressource a existé mais n'est plus disponible
            case EXPIRED -> problem(HttpStatus.GONE, "Pré-réservation expirée, la place a été remise en vente");
            case NOT_FOUND -> ResponseEntity.notFound().build();
        };
    }

    // RFC 9457 (application/problem+json) : format d'erreur standard que Spring sait produire
    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
