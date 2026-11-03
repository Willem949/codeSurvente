# Résultats des tests de charge

Généré par `scripts/benchmark.ps1` le 2026-10-27 17:16. Ne pas modifier à la main, relancer le script.

- Tir : 10000 acheteurs en 10 s (rampe), 500 places, 1 instance(s), Gatling sur la même machine
- Machine : AMD Ryzen 5 5600U with Radeon Graphics, PostgreSQL 17 local, pool Hikari par défaut (10 connexions)
- Connexions TCP partagées par Gatling (comme derrière un load balancer) : sans ça, Windows refuse les connexions au-delà de ~600/s et on mesure le système, pas les stratégies
- 201 (place obtenue) et 409 (complet) sont des réponses normales ; KO = erreurs 5xx ou timeouts
- Ligne `redis` indicative seulement : le Redis local est jedis-mock (serveur de test en Java, une commande à la fois), bien plus lent qu'un vrai Redis
- Latences en millisecondes, débit en requêtes par seconde

| Stratégie | Virtual threads | Réservations | Survente | Base cohérente | KO | Débit (req/s) | p50 | p95 | p99 |
|---|---|---|---|---|---|---|---|---|---|
| naive | non | 5031 | 4531 | False | 54 | 769.23 | 4529 | 6319 | 6454 |
| optimistic | non | 500 | 0 | True | 24 | 1000 | 71 | 3073 | 3724 |
| pessimistic | non | 500 | 0 | True | 0 | 1000 | 1011 | 1473 | 1519 |
| atomic | non | 500 | 0 | True | 294 | 1000 | 1 | 448 | 714 |
| check | non | 500 | 0 | True | 539 | 769.23 | 1989 | 2467 | 2478 |
| redis | non | 500 | 0 | True | 14 | 1000 | 384 | 1429 | 1527 |
| naive | oui | 5388 | 4888 | False | 2553 | 666.67 | 5392 | 6880 | 11033 |
| optimistic | oui | 500 | 0 | True | 2040 | 909.09 | 452 | 4697 | 6072 |
| pessimistic | oui | 500 | 0 | True | 4 | 909.09 | 1535 | 1969 | 3457 |
| atomic | oui | 500 | 0 | True | 376 | 909.09 | 3 | 1377 | 1751 |
| check | oui | 500 | 0 | True | 739 | 666.67 | 3838 | 4521 | 6888 |
| redis | oui | 500 | 0 | True | 334 | 909.09 | 1537 | 1716 | 2379 |
