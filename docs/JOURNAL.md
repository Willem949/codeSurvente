# Journal du projet Flash Sale

Fiche de référence : ce qui a été fait, pourquoi, comment s'en servir, et ce qu'il reste à faire.

---

## 1. Le projet en deux phrases

Un moteur de réservation capable de tenir une **vente flash** : 10 000 utilisateurs tentent de réserver 500 places en même temps. Il ne doit **jamais survendre**, doit rester rapide et doit fonctionner avec **plusieurs instances** de l'application.

L'objectif n'est pas seulement que ça marche : il faut **prouver** chaque problème par un test, puis **mesurer** chaque solution, pour avoir un projet démontrable en entretien client.

---

## 2. Architecture cible

```
                  Gatling (10 000 utilisateurs virtuels)
                               │
                    ┌──────────▼──────────┐
                    │   Load balancer     │
                    └──────────┬──────────┘
              ┌────────────────┼────────────────┐
        ┌─────▼────┐     ┌─────▼────┐     ┌─────▼────┐
        │ App #1   │     │ App #2   │     │ App #3   │   Spring Boot, sans état
        └────┬─────┘     └────┬─────┘     └────┬─────┘
             ├──────► Redis (compteur, file d'attente, idempotence) ◄──┤
             └──────► PostgreSQL = SOURCE DE VÉRITÉ ◄──────────────────┘
        Prometheus ◄── /actuator/prometheus ── Grafana
```

- **PostgreSQL est la seule source de vérité.** C'est lui qui garantit qu'on ne dépasse jamais 500 réservations.
- **Les instances sont sans état.** Une requête peut arriver sur n'importe laquelle, c'est pourquoi un `synchronized` (qui ne vaut que dans une JVM) ne protège rien.
- **Redis est un accélérateur**, pas une source de vérité : il filtre en quelques microsecondes les 9 500 personnes qui n'auront pas de place. S'il tombe, on le reconstruit depuis Postgres.
- **Pattern Strategy** : chaque stratégie anti-survente implémente la même interface `BookingStrategy`. On écrit les tests et les scénarios de charge une seule fois, puis on les lance sur toutes les stratégies pour les comparer avec des chiffres.

---

## 3. L'environnement de travail (et pourquoi il est comme ça)

Le poste est un PC d'entreprise **sans droits administrateur**. Conséquences :

| Outil | Situation | Pourquoi |
|---|---|---|
| **JDK 25** (Temurin) | Installé en ZIP dans `C:\Users\wkraiem\tools\jdk-25.0.4.1+1`, avec `JAVA_HOME` et le `Path` utilisateur | winget était bloqué (erreur `0x8a15000f`), et un ZIP ne demande aucun droit admin |
| **PostgreSQL 17** | Binaires ZIP dans `tools\pgsql`, données dans `tools\pgdata` | Fonctionne sans installeur ni service Windows |
| **Docker / Rancher** | ❌ Impossible pour l'instant | La virtualisation (WSL 2, « Plateforme d'ordinateur virtuel ») est désactivée, et l'activer demande les droits admin. Une demande est à faire auprès du manager / de l'IT |

**Pourquoi Java 25 et pas 21 ?** C'est aussi une version LTS. Depuis le JDK 24 (JEP 491), un virtual thread n'est plus bloqué sur son thread porteur (*pinning*) quand il entre dans un bloc `synchronized`. Ce sera utile à l'étape 3 (comparaison avec et sans virtual threads).

**Pourquoi Postgres 17 et pas 18 ?** La version de Flyway livrée avec Spring Boot connaît bien la 17, et les tests utilisent aussi un Postgres 17 : même moteur partout.

**Pourquoi Spring Boot 4.1.1 et pas 3 ?** Spring Boot 3.5 n'est plus proposé sur start.spring.io (son support open source s'est terminé en juin 2026). Pour ce projet, ça ne change rien. Les starters sont seulement plus découpés (`spring-boot-starter-flyway`, `spring-boot-starter-webmvc`…).

### Ce qui remplace Docker

| Prévu avec Docker | Remplacé par |
|---|---|
| Postgres dans `docker-compose` | Postgres ZIP lancé avec `pg_ctl` |
| Testcontainers | **`io.zonky.test:embedded-postgres`** : un vrai Postgres 17 démarré depuis un jar pendant les tests |
| Plusieurs instances en conteneurs | Plusieurs `java -jar` sur des ports différents |
| Prometheus, Grafana, load balancer | Versions ZIP Windows, le moment venu |
| Redis | À trancher à l'étape 4 (pas de version Windows officielle) |
| CI GitHub Actions | Inchangée : elle tourne sur les serveurs Linux de GitHub |

### Règle d'or : jamais H2

H2 ne gère pas `SELECT ... FOR UPDATE`, les niveaux d'isolation ni les verrous de lignes comme Postgres. Un test de concurrence qui passe sur H2 ne prouve rien pour la production. **Tous les tests tournent sur un vrai PostgreSQL.**

---

## 4. Ce qui a été fait : l'étape 0 (le squelette)

### Fichiers

| Fichier | Rôle |
|---|---|
| `pom.xml` | Spring Boot 4.1.1, Java 25, Web, JPA, Flyway (et son module Postgres), Validation, Actuator, `embedded-postgres` pour les tests |
| `src/main/resources/application.yml` | Connexion au Postgres local, `ddl-auto: validate`, `open-in-view: false` |
| `db/migration/V1__init.sql` | Tables `event` et `reservation` |
| `db/migration/V2__seed_event.sql` | Un événement « Concert » de 500 places |
| `event/` | Entité `Event`, `EventRepository`, DTO `EventResponse`, `EventController` (`GET /events/{id}`, 404 si l'événement n'existe pas) |
| `booking/` | Entité `Reservation`, enum `ReservationStatus`, `ReservationRepository` (prêts pour l'étape 1) |
| `test/.../PostgresIntegrationTest.java` | Classe de base des tests : démarre un Postgres embarqué une seule fois par JVM et y branche Spring |
| `test/.../event/EventIntegrationTest.java` | Vérifie que Flyway a créé le Concert avec 500 places, le JSON de l'endpoint et le 404 |

**Résultat :** `BUILD SUCCESS`, 3 tests passent. L'application lancée sur le Postgres local répond `{"id":1,"name":"Concert","totalSeats":500,"availableSeats":500}`.

### Les choix et leur raison

- **Packages par fonctionnalité** (`event/`, `booking/`) plutôt que par couche (`controller/`, `service/`…) : tout ce qui concerne la réservation est au même endroit.
- **Flyway crée le schéma, Hibernate le vérifie** (`ddl-auto: validate`). Le schéma est versionné et rejouable à l'identique partout. Hibernate ne modifie jamais la base dans notre dos.
- **Aucune contrainte `CHECK (available_seats >= 0)`, volontairement.** On veut d'abord *voir* la survente. La contrainte sera l'une des solutions de l'étape 2.
- **La colonne `version` existe, mais n'est pas mappée avec `@Version`.** Avec cette annotation, Hibernate activerait déjà le verrou optimiste et la version naïve ne pourrait plus survendre. On l'activera à l'étape 2.
- **`open-in-view: false`** : sinon Spring garde la session JPA, et donc une connexion à la base, ouverte jusqu'à la fin de la requête HTTP. Sous forte charge, le pool de connexions s'épuise.
- **Un DTO (`EventResponse`) plutôt que l'entité JPA** dans l'API : on contrôle ce qu'on expose, et on évite le chargement paresseux (*lazy loading*) déclenché pendant la sérialisation JSON.
- **`Reservation` garde un `eventId` plutôt qu'un `@ManyToOne`** : pas besoin de charger l'événement pour créer une réservation.

---

## 5. Comment s'en servir

```powershell
# Démarrer Postgres (à refaire après chaque redémarrage du PC)
& "$env:USERPROFILE\tools\pgsql\bin\pg_ctl.exe" -D "$env:USERPROFILE\tools\pgdata" -l "$env:USERPROFILE\tools\pg.log" start

# Arrêter Postgres
& "$env:USERPROFILE\tools\pgsql\bin\pg_ctl.exe" -D "$env:USERPROFILE\tools\pgdata" stop

# Se connecter à la base en ligne de commande
& "$env:USERPROFILE\tools\pgsql\bin\psql.exe" -U flashsale -d flashsale

# Lancer l'application, puis ouvrir http://localhost:8080/events/1
.\mvnw.cmd spring-boot:run

# Lancer les tests (n'ont PAS besoin du Postgres local : ils démarrent le leur)
.\mvnw.cmd test
```

**Si VS Code affiche des erreurs rouges** (`package does not match`, `cannot be resolved`) : `Ctrl+Shift+P` → « Java: Clean Java Language Server Workspace » → Restart. L'extension doit reconnaître le projet Maven.

**À faire :** un `git init` et un premier commit, pour pouvoir comparer l'avant et l'après de chaque stratégie.

---

## 6. Concepts déjà vus

### Compteur décrémenté ou `COUNT(*)` ?

| | Compteur `available_seats` | `COUNT(*)` des réservations |
|---|---|---|
| Lecture | Instantanée (une ligne) | De plus en plus lente |
| Anti-survente | Possible en une requête atomique : `UPDATE ... SET available_seats = available_seats - 1 WHERE available_seats > 0`, et une contrainte `CHECK` | Impossible sans verrouiller la table ou passer en `SERIALIZABLE` |
| Problème | **Ligne chaude** : toutes les écritures passent par la même ligne, donc une par une | **Check-then-act** : deux transactions comptent 499 en même temps et insèrent toutes les deux |

**On choisit le compteur**, parce que la survente est un problème de *correction* qu'il permet d'éliminer. La ligne chaude est un problème de *performance*, qu'on traitera ensuite (stock découpé en plusieurs lignes, Redis, file d'attente).

### Check-then-act (la cause de la survente)

```
Thread A : lit 1 place ──► 1 > 0 ✔ ──► écrit 0, crée une réservation
Thread B :  lit 1 place ──► 1 > 0 ✔ ──► écrit 0, crée une réservation
=> 2 réservations pour 1 place, et une décrémentation perdue
```

`@Transactional` ne protège pas : en `READ_COMMITTED` (le niveau par défaut de Postgres), les deux transactions lisent la même valeur sans se bloquer.

---

## 7. Ce qu'il reste à faire

### Étape 1 (en cours) : version naïve et test qui prouve la survente

1. `booking/strategy/BookingStrategy` (interface) et `BookingResult` (`SUCCESS` / `SOLD_OUT`).
2. `NaiveBookingStrategy` : lire, vérifier, décrémenter, créer la réservation.
3. `NaiveBookingStrategyTest` (étend `PostgresIntegrationTest`) :
   - 1 000 tentatives en parallèle sur 500 places (`Executors.newFixedThreadPool(50)`) ;
   - un `CountDownLatch(1)` comme pistolet de départ, pour que tous les threads partent en même temps ;
   - remise à zéro des données dans un `@BeforeEach` (la base est partagée entre les tests) ;
   - invariant vérifié : réservations ≤ 500 et `available_seats == 500 - réservations`.
   - **Ce test doit échouer** : c'est la preuve du bug.
4. Le `Test.java` à la racine (ébauche de `ne_doit_pas_survendre`) peut servir de point de départ, puis être supprimé.

**Question en attente :** si on ajoute `synchronized` sur `book()`, le test passe-t-il ? Pourquoi ça ne suffit pas en production ?

### Étapes suivantes

| Étape | Contenu | Adaptation sans Docker |
|---|---|---|
| 2 | Stratégies anti-survente, chacune avec son test : verrou optimiste (`@Version`), verrou pessimiste (`FOR UPDATE`), `UPDATE` atomique conditionnel, contrainte `CHECK` | Aucune |
| 3 | Tests de charge Gatling, comparaison chiffrée (débit, latence p99, erreurs), virtual threads activés ou non | Aucune |
| 4 | Plusieurs instances, load balancer, compteur Redis (`DECR` / script Lua) | Instances en `java -jar` ; solution Redis à choisir |
| 5 | Pré-réservation avec expiration et remise en vente, idempotence (double clic, retry réseau) | Aucune |
| 6 | File d'attente virtuelle, équité (premier arrivé, premier servi) | Dépend de Redis |
| 7 | Prometheus et Grafana, CI GitHub Actions, README de démo | Prometheus et Grafana en ZIP ; la CI peut utiliser Docker sur les serveurs GitHub |

---

## 8. Phrases pour l'entretien

- « J'ai isolé chaque stratégie de verrouillage derrière une interface commune, ce qui m'a permis de les benchmarker avec exactement le même test de charge. »
- « Postgres est la source de vérité, Redis n'est qu'un accélérateur : s'il tombe, on le reconstruit depuis la base, sans risque de survente. »
- « Mes tests de concurrence tournent sur un vrai PostgreSQL, jamais sur H2, parce que la sémantique des verrous diffère d'un moteur à l'autre. »
- « J'ai choisi Java 25 parce que depuis le JDK 24, `synchronized` ne bloque plus les virtual threads sur leur thread porteur. »
- « Je désactive l'open-in-view pour que chaque connexion à la base soit rendue dès la fin de la transaction. »
- « Un compteur de stock décrémenté atomiquement garantit l'absence de survente, au prix d'une ligne chaude. C'est un compromis entre correction et débit, et je l'ai mesuré. »
