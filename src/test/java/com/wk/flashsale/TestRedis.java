package com.wk.flashsale;

import com.github.fppt.jedismock.RedisServer;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;

/**
 * Serveur compatible Redis (jedis-mock, 100 % Java, scripts Lua compris), démarré une fois par JVM de test.
 * Pas de Docker sur le poste, donc pas de Testcontainers ; en CI, le même code tourne aussi contre un vrai Redis.
 * <p>
 * Toutes les classes de test avec Redis appellent {@link #register} : mêmes propriétés,
 * donc un seul contexte Spring partagé.
 */
public final class TestRedis {

    public static final int ADMISSIONS_PER_TICK = 2;

    private static final RedisServer SERVER = start();

    private TestRedis() {
    }

    private static RedisServer start() {
        try {
            // Port libre au hasard, en local uniquement (sinon le pare-feu Windows réclame les droits admin)
            RedisServer server = RedisServer.newRedisServer(0, InetAddress.getLoopbackAddress());
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.stop();
                } catch (IOException ignored) {
                    // arrêt de la JVM : rien de plus à faire
                }
            }));
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de démarrer le serveur Redis de test", e);
        }
    }

    /** À appeler depuis une méthode {@code @DynamicPropertySource}. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", SERVER::getHost);
        registry.add("spring.data.redis.port", SERVER::getBindPort);
        registry.add("flashsale.redis.enabled", () -> "true");
        registry.add("flashsale.queue.enabled", () -> "true");
        registry.add("flashsale.queue.admissions-per-tick", () -> String.valueOf(ADMISSIONS_PER_TICK));
        // Long, pour que le verrou d'un tick tienne pendant tout un test
        registry.add("flashsale.queue.tick-interval", () -> "PT1M");
    }
}
