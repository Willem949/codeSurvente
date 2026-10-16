package com.wk.flashsale;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
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
    }
}
