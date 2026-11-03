# Résultats des tests de charge

Généré par `scripts/benchmark.ps1` le 2026-11-03 14:41. Ne pas modifier à la main, relancer le script.

- Tir : 10000 acheteurs en 20 s (rampe), 500 places, 1 instance(s), Gatling sur la même machine
- Machine : AMD Ryzen 5 5600U with Radeon Graphics, PostgreSQL 17 local, pool Hikari de 10 connexions par instance
- **Cœurs isolés** (affinité processeur) : Gatling sur les cœurs logiques 0-3, l'application sur 4-7, Postgres et Redis sur 8-11
- Connexions TCP partagées par Gatling (comme derrière un load balancer) : sans ça, Windows refuse les connexions au-delà de ~600/s et on mesure le système, pas les stratégies
- 201 (place obtenue) et 409 (complet) sont des réponses normales ; KO = erreurs 5xx ou timeouts
- Ligne `redis` indicative seulement : le Redis local est jedis-mock (serveur de test en Java, une commande à la fois), bien plus lent qu'un vrai Redis
- Latences en millisecondes, débit en requêtes par seconde

| Stratégie | Virtual threads | Réservations | Survente | Base cohérente | KO | Débit (req/s) | p50 | p95 | p99 |
|---|---|---|---|---|---|---|---|---|---|
| naive | non | 780 | 280 | False | 0 | 500 | 1 | 30 | 144 |
| optimistic | non | 500 | 0 | True | 0 | 500 | 1 | 166 | 422 |
| pessimistic | non | 500 | 0 | True | 0 | 500 | 1 | 3 | 30 |
| atomic | non | 500 | 0 | True | 0 | 500 | 1 | 2 | 6 |
| check | non | 500 | 0 | True | 0 | 500 | 2 | 324 | 525 |
| redis | non | 500 | 0 | True | 0 | 500 | 1 | 1523 | 1757 |
