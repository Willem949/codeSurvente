package com.wk.flashsale;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Classe de base des tests d'intégration : démarre un VRAI PostgreSQL (jamais H2,
 * dont la sémantique de verrouillage diffère) une seule fois pour toute la JVM de test.
 * Flyway y applique les migrations au démarrage du contexte Spring.
 */
public abstract class PostgresIntegrationTest {

    protected static final long EVENT_ID = 1L;
    protected static final int SEATS = 500;

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    private static EmbeddedPostgres startPostgres() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // arrêt de la JVM : rien de plus à faire
                }
            }));
            return postgres;
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de démarrer le PostgreSQL embarqué", e);
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        // Les tests déclenchent eux-mêmes les jobs (expiration, file d'attente) au moment voulu
        registry.add("flashsale.scheduling.enabled", () -> "false");
    }

    /**
     * Remet le Concert dans son état initial, en SQL direct : ne dépend ni du cache JPA ni du code testé.
     * La base est partagée par toute la JVM de test, chaque classe doit la laisser propre.
     */
    protected static void resetConcert(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("DELETE FROM reservation");
        jdbcTemplate.update("UPDATE event SET total_seats = ?, available_seats = ? WHERE id = ?", SEATS, SEATS, EVENT_ID);
    }
}
