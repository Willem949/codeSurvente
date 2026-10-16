package com.wk.flashsale.booking.strategy;

import com.wk.flashsale.PostgresIntegrationTest;
import com.wk.flashsale.booking.ReservationRepository;
import com.wk.flashsale.event.Event;
import com.wk.flashsale.event.EventRepository;
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
class NaiveBookingStrategyTest extends PostgresIntegrationTest {

    private static final long EVENT_ID = 1L;
    private static final int SEATS = 500;
    private static final int ATTEMPTS = 1_000;
    private static final int THREADS = 50;

    @Autowired
    private NaiveBookingStrategy strategy;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private EventRepository eventRepository;

    @BeforeEach
    void resetDatabase() {
        // SQL direct : ne dépend ni du cache JPA ni du code testé
        jdbcTemplate.update("DELETE FROM reservation");
        jdbcTemplate.update("UPDATE event SET available_seats = ? WHERE id = ?", SEATS, EVENT_ID);
    }

    @Test
    void ne_doit_pas_survendre() throws Exception {
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

        // On vérifie ce qui est réellement en base : c'est elle qui fait foi
        Event event = eventRepository.findById(EVENT_ID).orElseThrow();
        long reservationCount = reservationRepository.countByEventId(EVENT_ID);

        System.out.printf("SUCCESS=%d, réservations en base=%d, places restantes=%d%n",
                successCount, reservationCount, event.getAvailableSeats());

        assertThat(reservationCount)
                .as("aucune survente : au plus %d réservations", SEATS)
                .isLessThanOrEqualTo(SEATS);
        // Conversion en long pour comparer des valeurs du même type que reservationCount
        assertThat((long) event.getAvailableSeats())
                .as("le compteur de places reflète les réservations")
                .isEqualTo(SEATS - reservationCount);
        assertThat((long) successCount)
                .as("chaque SUCCESS correspond à une réservation en base")
                .isEqualTo(reservationCount);
    }
}
