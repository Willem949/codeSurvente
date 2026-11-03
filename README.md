# Flash Sale — moteur de réservation sous forte concurrence

Projet perso pour comprendre ce qui se passe quand 10 000 personnes essaient de réserver 500 places en même temps.

Le but : ne jamais vendre plus de places qu'il n'y en a, rester rapide, et que ça tienne avec plusieurs instances de l'appli. J'avance étape par étape : d'abord un test qui reproduit le problème, ensuite la correction, et je compare les solutions avec des mesures.

## Où j'en suis

- [x] Squelette Spring Boot, schéma PostgreSQL versionné avec Flyway
- [x] Version naïve et test multi-thread qui prouve la survente
- [x] Stratégies anti-survente (verrou optimiste, pessimiste, UPDATE conditionnel, contrainte SQL)
- [x] Tests de charge Gatling et comparaison chiffrée
- [x] Plusieurs instances et Redis
- [x] Pré-réservation avec expiration, idempotence
- [x] File d'attente virtuelle
- [x] Métriques Prometheus et Grafana, CI

## Le problème, en un test

1 000 tentatives simultanées sur 500 places avec la version naïve (lire, vérifier, écrire) : **1 000 réservations acceptées**, et un compteur qui affiche encore ~380 places libres. Les transactions lisent toutes le même stock et s'écrasent les unes les autres. `@Transactional` ne protège pas : en `READ COMMITTED`, deux transactions lisent la même valeur sans se bloquer.

## Les solutions, mesurées

Même rafale (1 000 tentatives, 50 threads) dans les tests, puis 10 000 acheteurs en 10 s avec Gatling :

| Stratégie | Principe | Survente | p50 | p99 |
|---|---|---|---|---|
| Naïve | lire, vérifier, écrire | **~4 500 places en trop** | 4,5 s | 6,5 s |
| Verrou optimiste (`@Version`) | l'écriture échoue si la version a changé, on réessaie | 0 | 71 ms | 3,7 s |
| Verrou pessimiste (`FOR UPDATE`) | la lecture verrouille la ligne, les autres attendent | 0 | 1,0 s | 1,5 s |
| **`UPDATE` atomique conditionnel** | `... WHERE available_seats > 0`, une seule requête | 0 | **1 ms** | **0,7 s** |
| Contrainte `CHECK` | on décrémente sans vérifier, Postgres refuse le négatif | 0 | 2,0 s | 2,5 s |
| Redis + `UPDATE` atomique | Redis filtre les perdants, Postgres a le dernier mot | 0 | (384 ms)* | (1,5 s)* |

\* Mesuré avec jedis-mock, un serveur de test qui traite une commande à la fois : la correction est prouvée, la vitesse d'un vrai Redis non. La CI le teste contre un vrai Redis.

Résultats complets (avec et sans virtual threads) : [docs/BENCHMARK.md](docs/BENCHMARK.md). Mesures sur un portable, application et Gatling sur la même machine : ce sont les écarts entre stratégies qui comptent, pas les valeurs absolues.

Ce qu'on en retient :
- **L'`UPDATE` atomique gagne** : une fois tout vendu, `WHERE available_seats > 0` est faux, la requête ne prend aucun verrou et répond en une milliseconde.
- **Le pessimiste fait la queue même quand c'est complet** : chaque requête prend le verrou `FOR UPDATE` avant de découvrir qu'il n'y a plus rien.
- **L'optimiste a une longue traîne** : la plupart des tentatives entrent en conflit et recommencent.
- **La contrainte `CHECK` est un filet de sécurité, pas une stratégie** : chaque refus coûte une exception, un rollback et une ligne de log.
- **Les virtual threads n'aident pas ici** : le goulot est la ligne chaude et le pool de 10 connexions, pas le nombre de threads. Sans limite de threads, encore plus de requêtes s'entassent devant le pool.

## Architecture

```
            Gatling (10 000 acheteurs virtuels, répartis en tourniquet)
                    │              │              │
              ┌─────▼────┐   ┌─────▼────┐   ┌─────▼────┐
              │ App 8081 │   │ App 8082 │   │ App 8083 │   Spring Boot, sans état
              └────┬─────┘   └────┬─────┘   └────┬─────┘
                   ├──► Redis : copie du stock, file d'attente ◄──┤
                   └──► PostgreSQL : SEULE source de vérité ◄─────┘
              Prometheus ◄── /actuator/prometheus ── Grafana
```

- **PostgreSQL garantit l'absence de survente**, par un `UPDATE` atomique. Tout le reste est de l'optimisation.
- **Redis est un accélérateur** : un script Lua décrémente une copie du stock et répond « complet » aux 9 500 perdants sans toucher la base. S'il se trompe, Postgres refuse ; s'il tombe, tout passe par Postgres (testé).
- **Les instances sont sans état** : un `synchronized` ne protégerait qu'une JVM sur trois.

## Le parcours d'un acheteur

1. `POST /events/1/queue` : entrer dans la file d'attente, recevoir un ticket et sa position (ticket de boulangerie dans Redis : premier arrivé, premier servi, même entre plusieurs instances).
2. `GET /events/1/queue/{ticket}` : attendre d'être admis (N personnes admises par seconde, une seule instance fait avancer la file à chaque tick).
3. `POST /events/1/reservations` avec `Idempotency-Key` et `X-Queue-Token` : bloquer une place pour 10 minutes. Un double clic ou un retry renvoie la même réservation (index `UNIQUE` en base), le ticket ne sert qu'une fois.
4. `POST /reservations/{id}/confirm` : confirmer après paiement. Passé le délai, `410 Gone`, et un job remet la place en vente (`FOR UPDATE SKIP LOCKED` : plusieurs instances peuvent le lancer sans rendre deux fois la même place).

## Stack

Java 25, Spring Boot 4, PostgreSQL 17, Flyway, Redis (Lua), Micrometer / Prometheus / Grafana, Gatling, JUnit 5, GitHub Actions.

Les tests tournent sur un vrai PostgreSQL embarqué (pas H2, les verrous ne se comportent pas pareil) et sur un serveur compatible Redis écrit en Java (jedis-mock), faute de Docker sur le poste. La CI ajoute un tir Gatling sur deux instances avec un vrai Postgres et un vrai Redis.

## Lancer le projet

Il faut un JDK 25 et un PostgreSQL local avec une base `flashsale` (utilisateur `flashsale`, sans mot de passe).

```powershell
.\mvnw.cmd test                                   # tous les tests, sans rien installer
.\mvnw.cmd spring-boot:run                        # http://127.0.0.1:8080/events/1

.\scripts\local-redis.ps1                         # Redis de démo (jedis-mock) sur 6379
.\scripts\start-instances.ps1 -Count 3 -Redis -Queue
.\mvnw.cmd gatling:test "-Dstrategy=redis" "-DbaseUrls=http://127.0.0.1:8081,http://127.0.0.1:8082,http://127.0.0.1:8083"
.\scripts\stop-instances.ps1

.\scripts\benchmark.ps1                           # toutes les stratégies, régénère docs/BENCHMARK.md
```

Prometheus et Grafana : voir [ops/README.md](ops/README.md). Le détail des choix techniques est dans [docs/JOURNAL.md](docs/JOURNAL.md).
