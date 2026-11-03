# Résultats des tests de charge

Généré par `scripts/benchmark.ps1` le 2026-11-03 12:02. Ne pas modifier à la main, relancer le script.

- Tir : 10000 acheteurs en 10 s (rampe), 500 places, 1 instance(s), Gatling sur la même machine
- Machine : AMD Ryzen 5 5600U with Radeon Graphics, PostgreSQL 17 local, pool Hikari de 30 connexions par instance
- Connexions TCP partagées par Gatling (comme derrière un load balancer) : sans ça, Windows refuse les connexions au-delà de ~600/s et on mesure le système, pas les stratégies
- 201 (place obtenue) et 409 (complet) sont des réponses normales ; KO = erreurs 5xx ou timeouts
- Ligne `redis` indicative seulement : le Redis local est jedis-mock (serveur de test en Java, une commande à la fois), bien plus lent qu'un vrai Redis
- Latences en millisecondes, débit en requêtes par seconde

| Stratégie | Virtual threads | Réservations | Survente | Base cohérente | KO | Débit (req/s) | p50 | p95 | p99 |
|---|---|---|---|---|---|---|---|---|---|
| atomic | non | 500 | 0 | True | 0 | 909.09 | 3 | 1357 | 1975 |
| pessimistic | non | 500 | 0 | True | 0 | 1000 | 975 | 1683 | 1727 |
| atomic | oui | 500 | 0 | True | 97 | 909.09 | 811 | 3124 | 4566 |
| pessimistic | oui | 500 | 0 | True | 0 | 909.09 | 2245 | 3231 | 5478 |
