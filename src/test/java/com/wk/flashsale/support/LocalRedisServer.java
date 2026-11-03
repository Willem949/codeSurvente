package com.wk.flashsale.support;

import com.github.fppt.jedismock.RedisServer;

/**
 * Serveur compatible Redis pour la démo multi-instances sur ce poste (pas de Docker,
 * pas de Redis Windows officiel). Lancé par scripts/local-redis.ps1.
 * <p>
 * C'est un serveur de test : correct pour démontrer le partage d'état entre instances,
 * PAS représentatif des performances d'un vrai Redis (la CI utilise un vrai Redis).
 */
public final class LocalRedisServer {

    private LocalRedisServer() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6379;
        RedisServer server = RedisServer.newRedisServer(port);
        server.start();
        System.out.println("Redis (jedis-mock) à l'écoute sur le port " + server.getBindPort() + ". Ctrl+C pour arrêter.");
        Thread.currentThread().join();
    }
}
