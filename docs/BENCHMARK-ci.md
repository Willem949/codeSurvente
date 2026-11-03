# Résultats des tests de charge — CI GitHub, vrai Redis

Produits par le workflow `.github/workflows/benchmark.yml` (commit `e2b2571`, 2026-11-03), relus depuis ses annotations.
Il tourne à chaque push sur `main` qui touche le code : le résumé du job sur GitHub donne les chiffres à jour.

- Runner GitHub : 4 vCPU, 15 Go, Linux ; Gatling, 2 instances, PostgreSQL 17 et **Redis 7 (un vrai)** sur la même machine
- Tir : 10 000 acheteurs en 10 s (rampe), 500 places, répartis sur les 2 instances ; pool Hikari de 10 connexions, sans virtual threads
- 201 (place obtenue) et 409 (complet) sont des réponses normales ; KO = erreurs 5xx ou timeouts
- Latences en millisecondes, débit en requêtes par seconde

| Stratégie | Réservations | Survente | KO | Débit (req/s) | p50 | p95 | p99 |
|---|---|---|---|---|---|---|---|
| naive | 9 028 | **8 528** | 0 | 667 | 4 473 | 5 849 | 6 242 |
| optimistic | 500 | 0 | 0 | 1 000 | 2 288 | 5 496 | 6 276 |
| pessimistic | 500 | 0 | 0 | 1 000 | 2 | 1 066 | 1 435 |
| atomic | 500 | 0 | 0 | 1 000 | 1 | 299 | 444 |
| check | 500 | 0 | 0 | 1 000 | 1 | 251 | 413 |
| redis | 500 | 0 | 0 | 1 000 | 0 | 354 | 1 462 |

À retenir :
- **0 KO partout**, alors que le portable Windows refusait des connexions sous la même charge : la pile TCP de Linux tient mieux 1 000 nouvelles requêtes par seconde.
- **Avec un vrai Redis, la stratégie `redis` répond en 0 ms en médiane** : les 9 500 perdants reçoivent « complet » sans toucher Postgres. Sa traîne (p99) vient des 500 gagnants, qui passent quand même par l'`UPDATE` en base.
- **À deux instances, la version naïve est pire qu'à une** : 8 528 places en trop. Deux pools de connexions, c'est deux fois plus de lectures périmées en parallèle.
- L'optimiste s'effondre : sur une ligne aussi disputée, presque toutes les tentatives entrent en conflit et recommencent.
