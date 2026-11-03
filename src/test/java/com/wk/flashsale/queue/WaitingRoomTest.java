package com.wk.flashsale.queue;

import com.wk.flashsale.PostgresIntegrationTest;
import com.wk.flashsale.TestRedis;
import com.wk.flashsale.booking.HoldOutcome;
import com.wk.flashsale.booking.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class WaitingRoomTest extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        TestRedis.register(registry);
    }

    @Autowired
    private WaitingRoom waitingRoom;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void reset() {
        resetConcert(jdbcTemplate);
        waitingRoom.reset(EVENT_ID);
    }

    /** Simule le tick suivant (sur n'importe quelle instance) : le verrou du tick précédent est expiré. */
    private void nextTick() {
        redis.delete("queue:{" + EVENT_ID + "}:tick");
        waitingRoom.admitNextBatch();
    }

    @Test
    void premier_arrive_premier_servi() {
        QueueTicket first = waitingRoom.join(EVENT_ID);
        QueueTicket second = waitingRoom.join(EVENT_ID);
        QueueTicket third = waitingRoom.join(EVENT_ID);

        assertThat(List.of(first.position(), second.position(), third.position())).containsExactly(1L, 2L, 3L);

        nextTick(); // 2 admissions par tick dans les tests

        assertThat(status(first)).isEqualTo(new QueueTicket(first.token(), QueueTicket.Status.ADMITTED, 0));
        assertThat(status(second).status()).isEqualTo(QueueTicket.Status.ADMITTED);
        assertThat(status(third)).isEqualTo(new QueueTicket(third.token(), QueueTicket.Status.WAITING, 1));
    }

    @Test
    void mille_arrivees_simultanees_recoivent_mille_numeros_distincts() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(50);
        List<Future<QueueTicket>> futures = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            futures.add(pool.submit(() -> waitingRoom.join(EVENT_ID)));
        }
        List<Long> positions = new ArrayList<>();
        for (Future<QueueTicket> future : futures) {
            positions.add(future.get().position());
        }
        pool.shutdown();

        assertThat(positions).doesNotHaveDuplicates().hasSize(1_000);
        assertThat(positions).allMatch(p -> p >= 1 && p <= 1_000);
    }

    @Test
    void trois_instances_au_meme_tick_n_admettent_qu_un_lot() {
        for (int i = 0; i < 10; i++) {
            waitingRoom.join(EVENT_ID);
        }
        QueueTicket last = waitingRoom.join(EVENT_ID);

        // Le verrou NX : seule la première des trois fait avancer le curseur
        nextTick();
        waitingRoom.admitNextBatch();
        waitingRoom.admitNextBatch();

        assertThat(status(last).position()).isEqualTo(11 - TestRedis.ADMISSIONS_PER_TICK);
    }

    @Test
    void le_curseur_ne_prend_pas_d_avance_quand_la_file_est_vide() {
        nextTick();
        nextTick();
        QueueTicket late = waitingRoom.join(EVENT_ID);

        assertThat(late.status()).as("personne n'est admis d'avance pendant les ticks à vide")
                .isEqualTo(QueueTicket.Status.WAITING);
    }

    @Test
    void reserver_exige_un_ticket_admis_et_le_ticket_ne_sert_qu_une_fois() {
        QueueTicket ticket = waitingRoom.join(EVENT_ID);

        assertThat(reservationService.hold(EVENT_ID, "alice", "k1", null).status())
                .isEqualTo(HoldOutcome.Status.NOT_ADMITTED);
        assertThat(reservationService.hold(EVENT_ID, "alice", "k1", ticket.token()).status())
                .as("pas encore admis").isEqualTo(HoldOutcome.Status.NOT_ADMITTED);

        nextTick();

        assertThat(reservationService.hold(EVENT_ID, "alice", "k1", ticket.token()).status())
                .isEqualTo(HoldOutcome.Status.CREATED);
        assertThat(reservationService.hold(EVENT_ID, "alice", "k1", ticket.token()).status())
                .as("le retry avec la même clé est rejoué, même si le ticket est consommé")
                .isEqualTo(HoldOutcome.Status.REPLAYED);
        assertThat(reservationService.hold(EVENT_ID, "bob", "k2", ticket.token()).status())
                .as("ticket déjà utilisé : on ne le prête pas à un ami")
                .isEqualTo(HoldOutcome.Status.NOT_ADMITTED);
        assertThat(waitingRoom.status(EVENT_ID, ticket.token())).isEmpty();
    }

    private QueueTicket status(QueueTicket ticket) {
        return waitingRoom.status(EVENT_ID, ticket.token()).orElseThrow();
    }
}
