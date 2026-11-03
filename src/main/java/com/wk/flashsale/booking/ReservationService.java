package com.wk.flashsale.booking;

import com.wk.flashsale.FlashSaleProperties;
import com.wk.flashsale.event.EventRepository;
import com.wk.flashsale.queue.WaitingRoom;
import com.wk.flashsale.stock.StockGate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Parcours réel d'un acheteur (étape 5) : bloquer une place, payer, confirmer.
 * Une place bloquée et jamais confirmée est remise en vente à expiration.
 */
@Service
public class ReservationService {

    // Par lots : si 10 000 pré-réservations expirent en même temps, aucune transaction géante
    private static final int EXPIRY_BATCH_SIZE = 500;

    // Mêmes statuts que l'index unique partiel de la migration V5
    private static final List<ReservationStatus> ACTIVE_STATUSES = List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

    private final EventRepository eventRepository;
    private final ReservationRepository reservationRepository;
    private final StockGate stockGate;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;
    private final Duration holdTtl;
    // Vide si la file d'attente est désactivée : tout le monde peut réserver directement
    private final Optional<WaitingRoom> waitingRoom;

    public ReservationService(EventRepository eventRepository,
                              ReservationRepository reservationRepository,
                              StockGate stockGate,
                              Optional<WaitingRoom> waitingRoom,
                              JdbcTemplate jdbcTemplate,
                              PlatformTransactionManager transactionManager,
                              MeterRegistry meterRegistry,
                              FlashSaleProperties properties) {
        this.eventRepository = eventRepository;
        this.reservationRepository = reservationRepository;
        this.stockGate = stockGate;
        this.waitingRoom = waitingRoom;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.meterRegistry = meterRegistry;
        this.holdTtl = properties.hold().ttl();
    }

    /**
     * Bloque une place pour {@code holdTtl}. Rejouer la même {@code idempotencyKey}
     * (double clic, retry après un timeout) renvoie la même réservation, sans prendre une 2e place.
     *
     * @param queueToken ticket de la file d'attente, ignoré si elle est désactivée
     */
    public HoldOutcome hold(long eventId, String userId, String idempotencyKey, String queueToken) {
        HoldOutcome outcome = doHold(eventId, userId, idempotencyKey, queueToken);
        meterRegistry.counter("flashsale.holds", "outcome", outcome.status().name()).increment();
        return outcome;
    }

    private HoldOutcome doHold(long eventId, String userId, String idempotencyKey, String queueToken) {
        // 1. Cas le plus fréquent d'un retry : la première requête a abouti, on la renvoie telle quelle.
        //    Avant la file d'attente : le ticket a été consommé par la première requête
        Optional<Reservation> existing = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), eventId, userId);
        }

        // 2. Une réservation active par acheteur. Vérifié avant la file d'attente pour ne pas
        //    gaspiller son ticket ; l'index unique de la base couvre les requêtes simultanées
        if (hasActiveReservation(eventId, userId)) {
            // La première requête d'un double clic a pu commiter entre la lecture de l'étape 1 et celle-ci :
            // si c'est la même clé, c'est un rejeu, pas une seconde réservation (course trouvée par les tests)
            return reservationRepository.findByIdempotencyKey(idempotencyKey)
                    .map(mine -> replay(mine, eventId, userId))
                    .orElse(HoldOutcome.of(HoldOutcome.Status.ALREADY_RESERVED));
        }

        // 3. File d'attente : seul un ticket admis donne accès à la vente, et il ne sert qu'une fois
        if (waitingRoom.isPresent() && !waitingRoom.get().consumeIfAdmitted(eventId, queueToken)) {
            return HoldOutcome.of(HoldOutcome.Status.NOT_ADMITTED);
        }

        // 4. Filtre rapide (Redis si activé) : inutile d'aller en base si tout est vendu
        if (!stockGate.tryAcquire(eventId)) {
            return HoldOutcome.of(HoldOutcome.Status.SOLD_OUT);
        }

        // 5. Postgres tranche : UPDATE atomique + INSERT dans la même transaction
        try {
            Reservation created = transactionTemplate.execute(status -> {
                if (eventRepository.decrementAvailableSeats(eventId) == 0) {
                    return null;
                }
                // saveAndFlush : l'INSERT part maintenant, une clé en double lève l'exception ici
                // Tronqué à la microseconde, la précision de Postgres : la réponse d'un rejeu (relue en base)
                // doit être identique, au caractère près, à celle du premier appel
                Instant expiresAt = Instant.now().plus(holdTtl).truncatedTo(ChronoUnit.MICROS);
                return reservationRepository.saveAndFlush(Reservation.pending(eventId, userId, idempotencyKey, expiresAt));
            });
            return created == null ? HoldOutcome.of(HoldOutcome.Status.SOLD_OUT) : HoldOutcome.created(created);
        } catch (DataIntegrityViolationException duplicate) {
            // Un index UNIQUE a rejeté l'INSERT : la transaction est annulée et la place rendue en base.
            // On la rend aussi au portier, puis on regarde lequel des deux index a parlé
            stockGate.release(eventId, 1);
            // Même clé en même temps (double clic très rapide, deux instances) : on renvoie la gagnante
            Optional<Reservation> winner = reservationRepository.findByIdempotencyKey(idempotencyKey);
            if (winner.isPresent()) {
                return replay(winner.get(), eventId, userId);
            }
            // Clés différentes mais même acheteur au même instant (bot, onglets multiples)
            if (hasActiveReservation(eventId, userId)) {
                return HoldOutcome.of(HoldOutcome.Status.ALREADY_RESERVED);
            }
            throw duplicate;
        }
    }

    private boolean hasActiveReservation(long eventId, String userId) {
        return reservationRepository.existsByEventIdAndUserIdAndStatusIn(eventId, userId, ACTIVE_STATUSES);
    }

    private static HoldOutcome replay(Reservation reservation, long eventId, String userId) {
        if (reservation.getEventId() != eventId || !reservation.getUserId().equals(userId)) {
            return HoldOutcome.of(HoldOutcome.Status.KEY_REUSED);
        }
        return HoldOutcome.replayed(reservation);
    }

    public ConfirmOutcome confirm(long reservationId) {
        Integer updated = transactionTemplate.execute(status -> reservationRepository.confirmIfPending(reservationId, Instant.now()));
        if (updated != null && updated == 1) {
            meterRegistry.counter("flashsale.confirmations").increment();
            return ConfirmOutcome.CONFIRMED;
        }
        // Rien de modifié : on regarde pourquoi pour répondre précisément
        return reservationRepository.findById(reservationId)
                .map(reservation -> switch (reservation.getStatus()) {
                    // Confirmer deux fois n'est pas une erreur (retry du client après paiement)
                    case CONFIRMED -> ConfirmOutcome.CONFIRMED;
                    // PENDING mais date dépassée : le job ne l'a pas encore traitée, elle est quand même perdue
                    case PENDING, EXPIRED -> ConfirmOutcome.EXPIRED;
                })
                .orElse(ConfirmOutcome.NOT_FOUND);
    }

    public enum ConfirmOutcome {
        CONFIRMED,
        EXPIRED,
        NOT_FOUND
    }

    /**
     * Remet en vente les places des pré-réservations expirées.
     * Sûr avec plusieurs instances : FOR UPDATE SKIP LOCKED fait que deux jobs simultanés
     * se partagent les lignes au lieu de traiter deux fois la même.
     *
     * @return le nombre de places rendues
     */
    public int releaseExpiredHolds() {
        int total = 0;
        int released;
        do {
            Map<Long, Long> seatsByEvent = transactionTemplate.execute(status -> expireBatch());
            // Après le commit seulement : la base fait foi, Redis suit
            seatsByEvent.forEach((eventId, seats) -> stockGate.release(eventId, seats.intValue()));
            released = seatsByEvent.values().stream().mapToInt(Long::intValue).sum();
            total += released;
        } while (released == EXPIRY_BATCH_SIZE);

        if (total > 0) {
            meterRegistry.counter("flashsale.holds.expired").increment(total);
        }
        return total;
    }

    private Map<Long, Long> expireBatch() {
        List<Long> eventIds = jdbcTemplate.queryForList("""
                UPDATE reservation SET status = 'EXPIRED'
                WHERE id IN (
                    SELECT id FROM reservation
                    WHERE status = 'PENDING' AND expires_at <= now()
                    ORDER BY expires_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                RETURNING event_id""", Long.class, EXPIRY_BATCH_SIZE);

        Map<Long, Long> seatsByEvent = eventIds.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        seatsByEvent.forEach((eventId, seats) -> jdbcTemplate.update(
                "UPDATE event SET available_seats = available_seats + ? WHERE id = ?", seats, eventId));
        return seatsByEvent;
    }
}
