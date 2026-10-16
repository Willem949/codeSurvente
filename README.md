# Flash Sale — moteur de réservation sous forte concurrence

Projet perso pour comprendre ce qui se passe quand 10 000 personnes essaient de réserver 500 places en même temps.

Le but : ne jamais vendre plus de places qu'il n'y en a, rester rapide, et que ça tienne avec plusieurs instances de l'appli. J'avance étape par étape : d'abord un test qui reproduit le problème, ensuite la correction, et je compare les solutions avec des mesures.

## Où j'en suis

- [x] Squelette Spring Boot, schéma PostgreSQL versionné avec Flyway
- [x] Version naïve et test multi-thread qui prouve la survente
- [ ] Stratégies anti-survente (verrou optimiste, pessimiste, UPDATE conditionnel, contrainte SQL)
- [ ] Tests de charge Gatling et comparaison chiffrée
- [ ] Plusieurs instances et Redis
- [ ] Pré-réservation avec expiration, idempotence
- [ ] File d'attente virtuelle
- [ ] Métriques Prometheus et Grafana, CI

Premier résultat avec la version naïve : 1 000 tentatives sur 500 places, 1 000 réservations acceptées, et un compteur qui affiche encore 392 places libres. Les transactions lisent toutes le même stock et s'écrasent les unes les autres.

## Stack

Java 25, Spring Boot 4, PostgreSQL 17, Flyway, JUnit 5. Les tests tournent sur un vrai PostgreSQL embarqué (pas H2, les verrous ne se comportent pas pareil).

## Lancer le projet

Il faut un JDK 25 et un PostgreSQL local avec une base `flashsale` (utilisateur `flashsale`, sans mot de passe).

```bash
./mvnw spring-boot:run      # puis http://localhost:8080/events/1
./mvnw test                 # pas besoin du Postgres local, les tests démarrent le leur
```

Le détail des choix techniques est dans [docs/JOURNAL.md](docs/JOURNAL.md).
