package com.wk.flashsale.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    long countByEventId(Long eventId);

    long countByEventIdAndStatus(Long eventId, ReservationStatus status);

    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    /**
     * Confirme une pré-réservation seulement si elle est encore PENDING et pas expirée.
     * Comme pour le stock, la vérification et l'écriture tiennent en une requête : le job
     * d'expiration ne peut pas remettre la place en vente pendant qu'on la confirme.
     *
     * @return 1 si confirmée, 0 sinon (déjà confirmée, expirée ou inconnue)
     */
    @Modifying
    @Query("""
            UPDATE Reservation r SET r.status = com.wk.flashsale.booking.ReservationStatus.CONFIRMED
            WHERE r.id = :id
              AND r.status = com.wk.flashsale.booking.ReservationStatus.PENDING
              AND r.expiresAt > :now""")
    int confirmIfPending(@Param("id") long id, @Param("now") Instant now);
}
