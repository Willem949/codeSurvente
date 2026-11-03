package com.wk.flashsale.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "reservation")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Simple identifiant plutôt qu'une relation @ManyToOne : évite de charger l'Event
    // et garde la réservation indépendante de l'agrégat "event"
    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // Renseignés seulement pour une pré-réservation (étape 5)
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    protected Reservation() {
        // requis par JPA
    }

    public Reservation(Long eventId, String userId, ReservationStatus status) {
        this.eventId = eventId;
        this.userId = userId;
        this.status = status;
        this.createdAt = Instant.now();
    }

    /** Pré-réservation : la place est bloquée jusqu'à {@code expiresAt}, puis remise en vente si elle n'est pas confirmée. */
    public static Reservation pending(Long eventId, String userId, String idempotencyKey, Instant expiresAt) {
        Reservation reservation = new Reservation(eventId, userId, ReservationStatus.PENDING);
        reservation.idempotencyKey = idempotencyKey;
        reservation.expiresAt = expiresAt;
        return reservation;
    }

    public Long getId() {
        return id;
    }

    public Long getEventId() {
        return eventId;
    }

    public String getUserId() {
        return userId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
