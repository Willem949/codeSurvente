package com.wk.flashsale.booking;

import com.wk.flashsale.PostgresIntegrationTest;
import com.wk.flashsale.event.EventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReservationServiceTest extends PostgresIntegrationTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void reset() {
        resetConcert(jdbcTemplate);
    }

    @Test
    void une_pre_reservation_bloque_une_place_jusqu_a_expiration() {
        HoldOutcome outcome = reservationService.hold(EVENT_ID, "alice", "key-1", null);

        assertThat(outcome.status()).isEqualTo(HoldOutcome.Status.CREATED);
        assertThat(outcome.reservation().getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(outcome.reservation().getExpiresAt()).isAfter(outcome.reservation().getCreatedAt());
        assertThat(availableSeats()).isEqualTo(SEATS - 1);
    }

    @Test
    void rejouer_la_meme_cle_renvoie_la_meme_reservation_sans_prendre_une_autre_place() {
        HoldOutcome first = reservationService.hold(EVENT_ID, "alice", "key-1", null);
        HoldOutcome retry = reservationService.hold(EVENT_ID, "alice", "key-1", null);

        assertThat(retry.status()).isEqualTo(HoldOutcome.Status.REPLAYED);
        assertThat(retry.reservation().getId()).isEqualTo(first.reservation().getId());
        assertThat(availableSeats()).isEqualTo(SEATS - 1);
    }

    @Test
    void cinquante_doubles_clics_simultanes_ne_creent_qu_une_reservation() throws Exception {
        // Le cas difficile : aucune requête ne voit encore la réservation des autres,
        // c'est l'index UNIQUE de Postgres qui départage
        List<HoldOutcome> outcomes = concurrently(50, i -> reservationService.hold(EVENT_ID, "alice", "same-key", null));

        assertThat(outcomes).filteredOn(o -> o.status() == HoldOutcome.Status.CREATED).hasSize(1);
        assertThat(outcomes).allMatch(o -> o.status() == HoldOutcome.Status.CREATED || o.status() == HoldOutcome.Status.REPLAYED);
        assertThat(outcomes).extracting(o -> o.reservation().getId()).containsOnly(outcomes.getFirst().reservation().getId());
        assertThat(reservationRepository.countByEventId(EVENT_ID)).isEqualTo(1);
        assertThat(availableSeats()).as("les places prises par les perdants ont été rendues").isEqualTo(SEATS - 1);
    }

    @Test
    void une_cle_reutilisee_pour_un_autre_utilisateur_est_refusee() {
        reservationService.hold(EVENT_ID, "alice", "key-1", null);

        HoldOutcome outcome = reservationService.hold(EVENT_ID, "bob", "key-1", null);

        assertThat(outcome.status()).isEqualTo(HoldOutcome.Status.KEY_REUSED);
        assertThat(availableSeats()).isEqualTo(SEATS - 1);
    }

    @Test
    void mille_clients_pour_cinq_cents_places_pas_de_survente() throws Exception {
        List<HoldOutcome> outcomes = concurrently(1_000, i -> reservationService.hold(EVENT_ID, "user-" + i, "key-" + i, null));

        assertThat(outcomes).filteredOn(o -> o.status() == HoldOutcome.Status.CREATED).hasSize(SEATS);
        assertThat(outcomes).filteredOn(o -> o.status() == HoldOutcome.Status.SOLD_OUT).hasSize(1_000 - SEATS);
        assertThat(availableSeats()).isZero();
    }

    @Test
    void confirmer_avant_expiration_rend_la_reservation_definitive() {
        long id = reservationService.hold(EVENT_ID, "alice", "key-1", null).reservation().getId();

        assertThat(reservationService.confirm(id)).isEqualTo(ReservationService.ConfirmOutcome.CONFIRMED);
        assertThat(reservationService.confirm(id)).as("confirmer deux fois n'est pas une erreur")
                .isEqualTo(ReservationService.ConfirmOutcome.CONFIRMED);
        assertThat(reservationRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void une_pre_reservation_expiree_ne_peut_plus_etre_confirmee() {
        long id = reservationService.hold(EVENT_ID, "alice", "key-1", null).reservation().getId();
        expireAllPendingHolds();

        // Même avant le passage du job : la date fait foi
        assertThat(reservationService.confirm(id)).isEqualTo(ReservationService.ConfirmOutcome.EXPIRED);
        assertThat(reservationService.confirm(999_999)).isEqualTo(ReservationService.ConfirmOutcome.NOT_FOUND);
    }

    @Test
    void les_places_expirees_sont_remises_en_vente_et_revendues() throws Exception {
        concurrently(SEATS, i -> reservationService.hold(EVENT_ID, "user-" + i, "key-" + i, null));
        assertThat(reservationService.hold(EVENT_ID, "late", "late-key", null).status())
                .as("tout est bloqué par des pré-réservations").isEqualTo(HoldOutcome.Status.SOLD_OUT);

        expireAllPendingHolds();
        int released = reservationService.releaseExpiredHolds();

        assertThat(released).isEqualTo(SEATS);
        assertThat(availableSeats()).isEqualTo(SEATS);
        assertThat(reservationRepository.countByEventIdAndStatus(EVENT_ID, ReservationStatus.EXPIRED)).isEqualTo(SEATS);
        assertThat(reservationService.hold(EVENT_ID, "late", "late-key-2", null).status())
                .isEqualTo(HoldOutcome.Status.CREATED);
    }

    @Test
    void deux_jobs_d_expiration_simultanes_ne_rendent_pas_deux_fois_la_meme_place() throws Exception {
        concurrently(SEATS, i -> reservationService.hold(EVENT_ID, "user-" + i, "key-" + i, null));
        expireAllPendingHolds();

        // Comme trois instances dont le job part à la même seconde
        List<Integer> released = concurrently(3, i -> reservationService.releaseExpiredHolds());

        assertThat(released.stream().mapToInt(Integer::intValue).sum()).isEqualTo(SEATS);
        assertThat(availableSeats()).as("ni plus ni moins que les places réellement libérées").isEqualTo(SEATS);
    }

    private void expireAllPendingHolds() {
        jdbcTemplate.update("UPDATE reservation SET expires_at = now() - interval '1 second' WHERE status = 'PENDING'");
    }

    private int availableSeats() {
        return eventRepository.findById(EVENT_ID).orElseThrow().getAvailableSeats();
    }

    private interface Attempt<T> {
        T run(int index) throws Exception;
    }

    private static <T> List<T> concurrently(int count, Attempt<T> attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(count, 50));
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                startSignal.await();
                return attempt.run(index);
            }));
        }
        startSignal.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();
        return results;
    }
}
