package com.wk.flashsale.booking.strategy;

import com.wk.flashsale.TestRedis;
import com.wk.flashsale.event.EventRepository;
import com.wk.flashsale.stock.RedisStockGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class RedisBookingStrategyTest extends AbstractBookingConcurrencyTest {

    private static final String STOCK_KEY = "stock:{" + EVENT_ID + "}";

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        TestRedis.register(registry);
    }

    @Autowired
    private RedisBookingStrategy strategy;

    @Autowired
    private RedisStockGate stockGate;

    @Autowired
    private AtomicUpdateBookingStrategy atomic;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private StringRedisTemplate redis;

    // Après le reset de la base (méthode de la classe mère, exécutée avant celle-ci)
    @BeforeEach
    @AfterEach
    void resetRedis() {
        stockGate.reset(EVENT_ID);
    }

    @Test
    void vend_exactement_toutes_les_places_sans_survente() throws Exception {
        assertSoldExactlyAllSeats(runConcurrentBookings(strategy));

        assertThat(redis.opsForValue().get(STOCK_KEY))
                .as("le compteur Redis a été chargé depuis Postgres puis décrémenté jusqu'à 0")
                .isEqualTo("0");
    }

    @Test
    void un_compteur_redis_faux_ne_fait_pas_survendre_postgres_a_le_dernier_mot() throws Exception {
        // Redis croit qu'il reste 10 000 places (désynchronisé, bug, mauvaise reconstruction...)
        redis.opsForValue().set(STOCK_KEY, "10000");

        assertSoldExactlyAllSeats(runConcurrentBookings(strategy));
    }

    @Test
    void redis_en_panne_les_reservations_passent_par_postgres() throws Exception {
        // Un portier branché sur un port où rien n'écoute : chaque appel Redis échoue
        LettuceConnectionFactory deadRedis = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1));
        deadRedis.afterPropertiesSet();
        try {
            RedisBookingStrategy degraded = new RedisBookingStrategy(
                    new RedisStockGate(new StringRedisTemplate(deadRedis), eventRepository), atomic);

            assertSoldExactlyAllSeats(runConcurrentBookings(degraded));
        } finally {
            deadRedis.destroy();
        }
    }
}
