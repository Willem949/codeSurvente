# Résultats des tests de charge

Généré par `scripts/benchmark.ps1` le 2026-11-03 14:29. Ne pas modifier à la main, relancer le script.

- Tir : 10000 acheteurs en 10 s (rampe), 500 places, 1 instance(s), Gatling sur la même machine
- Machine : AMD Ryzen 5 5600U with Radeon Graphics, PostgreSQL 17 local, pool Hikari de 10 connexions par instance
- **Cœurs isolés** (affinité processeur) : Gatling sur les cœurs logiques 0-3, l'application sur 4-7, Postgres et Redis sur 8-11
- Connexions TCP partagées par Gatling (comme derrière un load balancer) : sans ça, Windows refuse les connexions au-delà de ~600/s et on mesure le système, pas les stratégies
- 201 (place obtenue) et 409 (complet) sont des réponses normales ; KO = erreurs 5xx ou timeouts
- Ligne `redis` indicative seulement : le Redis local est jedis-mock (serveur de test en Java, une commande à la fois), bien plus lent qu'un vrai Redis
- Latences en millisecondes, débit en requêtes par seconde

| Stratégie | Virtual threads | Réservations | Survente | Base cohérente | KO | Débit (req/s) | p50 | p95 | p99 |
|---|---|---|---|---|---|---|---|---|---|
| naive | non | 4389 | 3889 | False | 721 | 909.09 | 1786 | 3277 | 3880 |
| optimistic | non | 500 | 0 | True | 388 | 909.09 | 118 | 2450 | 4043 |
| pessimistic | non | 500 | 0 | True | 48 | 909.09 | 1321 | 2167 | 3046 |
| atomic | non | 500 | 0 | True | 0 | 1000 | 1 | 53 | 228 |
| check | non | 500 | 0 | True | 0 | 714.29 | 3413 | 3905 | 3929 |
| redis | non | 500 | 0 | True | 809 | 833.33 | 2671 | 3574 | 3972 |
| naive | oui | 5469 | 4969 | False | 3236 | 588.24 | 5454 | 7795 | 10623 |
| optimistic | oui | 500 | 0 | True | 2152 | 909.09 | 762 | 5572 | 6631 |
| pessimistic | oui | 500 | 0 | True | 70 | 769.23 | 2602 | 4297 | 5998 |
| atomic | oui | 500 | 0 | True | 0 | 909.09 | 1 | 504 | 1420 |
| check | oui | 500 | 0 | True | 0 | 769.23 | 3029 | 3362 | 6288 |
| redis | oui | 500 | 0 | True | 122 | 909.09 | 304 | 2942 | 3095 |
