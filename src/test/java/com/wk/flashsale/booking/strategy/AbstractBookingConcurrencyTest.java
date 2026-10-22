package com.wk.flashsale.booking.strategy;

import com.wk.flashsale.PostgresIntegrationTest;
import com.wk.flashsale.booking.ReservationRepository;
import com.wk.flashsale.event.EventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Socle commun des tests de concurrence : chaque stratégie subit exactement la même rafale
 * (1 000 tentatives simultanées sur 500 places), ce qui rend les résultats comparables.
 */
// Surtout PAS de @Transactional ici : le reset doit être validé et visible par les threads de réservation
@SpringBootTest
abstract class AbstractBookingConcurrencyTest extends PostgresIntegrationTest {

    protected static final long EVENT_ID = 1L;
    protected static final int SEATS = 500;
    protected static final int ATTEMPTS = 1_000;
    private static final int THREADS = 50;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private EventRepository eventRepository;

    /** Ce que la rafale a produit : réponses renvoyées aux clients et état réel de la base. */
    protected record Outcome(int successCount, long reservationCount, int availableSeats) {
    }

    // Aussi après : la base est partagée par toute la JVM de test, les autres classes
    // (EventIntegrationTest) s'attendent à trouver le Concert intact avec ses 500 places
    @BeforeEach
    @AfterEach
    void resetDatabase() {
        // SQL direct : ne dépend ni du cache JPA ni du code testé
        jdbcTemplate.update("DELETE FROM reservation");
        jdbcTemplate.update("UPDATE event SET available_seats = ? WHERE id = ?", SEATS, EVENT_ID);
    }

    protected Outcome runConcurrentBookings(BookingStrategy strategy) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<BookingResult>> futures = new ArrayList<>();

        for (int i = 0; i < ATTEMPTS; i++) {
            String userId = "user-" + i;
            futures.add(pool.submit(() -> {
                startSignal.await();               // tous les threads attendent ici...
                return strategy.book(EVENT_ID, userId);
            }));
        }
        startSignal.countDown();                   // ... et partent ensemble

        // get() attend la fin de chaque thread et relance son exception s'il a planté
        int successCount = 0;
        for (Future<BookingResult> future : futures) {
            if (future.get() == BookingResult.SUCCESS) {
                successCount++;
            }
        }
        pool.shutdown();

        // On relit la base : c'est elle qui fait foi
        Outcome outcome = new Outcome(
                successCount,
                reservationRepository.countByEventId(EVENT_ID),
                eventRepository.findById(EVENT_ID).orElseThrow().getAvailableSeats());

        System.out.printf("[%s] SUCCESS=%d, réservations en base=%d, places restantes=%d%n",
                // getUserClass : le vrai nom de la classe, sans le proxy créé par Spring pour @Transactional
                ClassUtils.getUserClass(strategy).getSimpleName(),
                outcome.successCount(), outcome.reservationCount(), outcome.availableSeats());
        return outcome;
    }

    /** Invariant des stratégies correctes : 1 000 clients pour 500 places, tout part, ni plus ni moins. */
    protected static void assertSoldExactlyAllSeats(Outcome outcome) {
        assertThat(outcome.reservationCount())
                .as("exactement %d réservations : ni survente, ni sous-vente", SEATS)
                .isEqualTo(SEATS);
        assertThat(outcome.availableSeats())
                .as("plus aucune place restante")
                .isZero();
        assertThat(outcome.successCount())
                .as("autant de SUCCESS que de réservations en base")
                .isEqualTo(SEATS);
    }
}
