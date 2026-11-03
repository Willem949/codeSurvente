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
| Redis | **jedis-mock** : serveur compatible Redis écrit en Java (scripts Lua compris), pour les tests et la démo locale. Aucun binaire à installer. La CI utilise un vrai Redis |
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

---

## 5 bis. Étapes 1 et 2 : la survente, puis quatre façons de l'empêcher

### Le banc d'essai commun

`AbstractBookingConcurrencyTest` lance la même rafale sur n'importe quelle `BookingStrategy` : 1 000 tentatives, 50 threads, un `CountDownLatch` comme pistolet de départ. La base est remise à zéro **avant et après** chaque test, en SQL direct : elle est partagée par toute la JVM de test, et `EventIntegrationTest` doit retrouver le Concert intact. Les stratégies correctes vérifient toutes `assertSoldExactlyAllSeats` : exactement 500 réservations, 0 place restante, 500 `SUCCESS`.

### Résultats

| Stratégie | Réservations | Places restantes | Test |
|---|---|---|---|
| `NaiveBookingStrategy` | 1 000 | ~370 (varie) | vert : il vérifie que la survente se produit (inversé à l'étape 7, voir 5 ter) |
| `OptimisticLockBookingStrategy` | 500 | 0 | vert |
| `PessimisticLockBookingStrategy` | 500 | 0 | vert |
| `AtomicUpdateBookingStrategy` | 500 | 0 | vert |
| `CheckConstraintBookingStrategy` | 500 | 0 | vert |

### Les choix et leur raison

- **Verrou optimiste : une seconde entité `VersionedEvent` sur la même table**, avec `@Version`. Mettre `@Version` sur `Event` aurait aussi protégé la version naïve, et son test n'aurait plus rien prouvé.
- **Verrou optimiste : la boucle de réessai est hors transaction** (`TransactionTemplate` dans une boucle, pas `@Transactional` sur `book()`). Une transaction en conflit est perdue, il faut en rouvrir une pour relire le stock à jour. Chaque conflit signifie qu'un autre client a réussi, donc le stock avance toujours. La limite (`MAX_ATTEMPTS = 200`, puis `BookingResult.CONFLICT`) évite seulement qu'un client tourne indéfiniment.
- **Verrou pessimiste : `@Lock(PESSIMISTIC_WRITE)`** sur une requête dédiée `findByIdForUpdate`, qui génère `SELECT ... FOR UPDATE`. Le code reste le check-then-act naïf, seule la lecture change.
- **Contrainte `CHECK` (migration `V3`)** : `available_seats >= 0`. La stratégie décrémente sans condition et traduit le refus de Postgres (`DataIntegrityViolationException`, SQLState `23514`) en `SOLD_OUT`. L'exception est attrapée **hors** de la transaction, car après une erreur Postgres refuse toute autre requête dans la même transaction. La contrainte reste en place pour toutes les stratégies : c'est un filet de sécurité au niveau du schéma. Elle n'empêche pas la version naïve de survendre, car celle-ci écrit des valeurs positives mais périmées.
- Les warnings `SQLState: 23514` affichés pendant `CheckConstraintBookingStrategyTest` sont **normaux** : ce sont les 500 tentatives en trop refusées par la base.

### Ce que les tests ne montrent pas encore

Elles sont toutes correctes, mais pas équivalentes. L'optimiste multiplie les transactions perdues sur une ligne aussi disputée. Le pessimiste et l'`UPDATE` atomique font la queue sur le verrou de la ligne. La contrainte paie le coût d'une exception par refus. C'est l'objet de l'étape 3 : le mesurer.

---

## 5 ter. Étapes 3 à 7 : mesurer, passer à plusieurs instances, le vrai parcours d'achat

### Étape 3 : tests de charge

| Fichier | Rôle |
|---|---|
| `bench/BenchmarkController` | `POST /bench/events/{id}/bookings?strategy=atomic&userId=…`, `POST …/reset`, `GET …/check`. **Profil `bench` uniquement** : un reset n'a rien à faire en production |
| `load/FlashSaleSimulation` (test) | Gatling : 10 000 acheteurs en 10 s sur une stratégie. Reset avant, lecture de `/check` après : c'est la base qui dit s'il y a eu survente, pas les réponses HTTP |
| `scripts/benchmark.ps1` | Lance toutes les stratégies, avec et sans virtual threads, après un tir de préchauffage, et écrit `docs/BENCHMARK.md` |

- Les beans de stratégie ont un nom court (`@Component("atomic")`…) : le contrôleur reçoit une `Map<String, BookingStrategy>` injectée par Spring, sans `switch` à maintenir.
- **201 et 409 sont des réponses normales.** Seuls les 5xx et les timeouts sont des KO.
- **Gatling partage les connexions TCP** (`shareConnections`). Au premier essai, chaque utilisateur virtuel ouvrait sa connexion : 1 000 nouvelles connexions par seconde, et Windows répondait `Connection refused` au-delà d'environ 600/s. On mesurait la pile TCP du portable, plus les stratégies. Avec les connexions partagées, le serveur suit le rythme du tir.
- Leçons des chiffres (détail dans le README et `docs/BENCHMARK.md`) : l'`UPDATE` atomique est de loin le plus rapide (une fois tout vendu, la condition est fausse, aucun verrou n'est pris) ; le pessimiste fait la queue sur `FOR UPDATE` même quand c'est complet ; l'optimiste a une longue traîne de réessais ; la contrainte `CHECK` paie une exception, un rollback et un log par refus ; **les virtual threads dégradent les résultats** ici, car le goulot est le pool de 10 connexions et la ligne chaude : sans la limite des 200 threads de Tomcat, encore plus de requêtes s'entassent devant le pool.
- **Limite connue** : il reste des KO résiduels dans certains tirs (quelques centaines sur 10 000), dont la cause n'a pas été isolée faute de temps. La correction, elle, est vérifiée à chaque tir : 0 survente, base cohérente.

### Étape 4 : plusieurs instances et Redis

| Fichier | Rôle |
|---|---|
| `stock/StockGate` | Le « portier » : `tryAcquire` répond vite « complet », `release` rend des places. Deux implémentations, choisies par `flashsale.redis.enabled` |
| `stock/RedisStockGate` | Script Lua (lire, vérifier, décrémenter d'un bloc). Compteur absent : rechargé depuis Postgres avec `SETNX`. Redis en panne : on laisse passer, Postgres tranche |
| `stock/DatabaseOnlyStockGate` | Sans Redis : laisse tout passer |
| `booking/strategy/RedisBookingStrategy` | Portier Redis, puis `UPDATE` atomique. Si la base échoue, la place est rendue à Redis |
| `scripts/start-instances.ps1`, `stop-instances.ps1`, `local-redis.ps1` | N instances en `java -jar` sur 8081, 8082…, et un Redis de démo (jedis-mock) |

- **Pas de load balancer à installer** : Gatling accepte plusieurs `baseUrls` et répartit les utilisateurs en tourniquet.
- Clés Redis avec *hash tag* (`stock:{1}`) : avec Redis Cluster, toutes les clés d'un événement restent sur le même nœud, condition pour qu'un script Lua les manipule ensemble.
- Trois tests le prouvent : vente exacte avec Redis ; **Redis qui croit à 10 000 places** (toujours 500 vendues, Postgres a le dernier mot) ; **Redis injoignable** (500 vendues en passant par Postgres).

### Étape 5 : pré-réservation et idempotence

| Fichier | Rôle |
|---|---|
| `V4__hold_and_idempotency.sql` | `expires_at`, `idempotency_key` avec index `UNIQUE`, index partiel sur les `PENDING` |
| `booking/ReservationService` | `hold` (bloquer), `confirm` (confirmer), `releaseExpiredHolds` (remettre en vente) |
| `booking/ReservationController` | `POST /events/{id}/reservations` (header `Idempotency-Key` obligatoire), `POST /reservations/{id}/confirm` |
| `booking/ReservationExpiryJob` | Toutes les 5 s, sur chaque instance |

- **Idempotence à deux niveaux** : on cherche d'abord la clé (cas du retry, le plus fréquent) ; si deux requêtes arrivent ensemble, l'index `UNIQUE` rejette la seconde, sa transaction est annulée (la place revient en base), on la rend au portier et on renvoie la réservation gagnante. Testé avec 50 doubles clics simultanés : 1 réservation, 1 place prise.
- **Réponse identique au caractère près** : `expires_at` est tronqué à la microseconde (précision de Postgres), sinon le rejeu, relu en base, différait du premier appel. Bug trouvé par le test de l'API.
- Codes HTTP : 201 créé, 200 rejeu, 409 complet, 422 clé réutilisée pour une autre demande, 403 pas admis par la file, 204 confirmé, 410 expiré. Erreurs au format `application/problem+json` (RFC 9457).
- **Confirmer = un `UPDATE … WHERE status = 'PENDING' AND expires_at > now`** : vérification et écriture en une requête, comme pour le stock. Le job ne peut pas remettre la place en vente pendant qu'on la confirme.
- **Expiration sûre avec plusieurs instances** : `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING event_id`. Deux jobs simultanés se partagent les lignes au lieu de rendre deux fois la même place (testé avec 3 jobs en parallèle). Par lots de 500, pour éviter une transaction géante.

### Étape 6 : file d'attente virtuelle

| Fichier | Rôle |
|---|---|
| `queue/WaitingRoom` | Ticket de boulangerie dans Redis : `INCR` donne un numéro strictement croissant (FIFO même avec 3 instances), un curseur « admis jusqu'au n° X » avance de N à chaque tick |
| `queue/QueueController` | `POST /events/{id}/queue` → ticket et position ; `GET /events/{id}/queue/{ticket}` → `WAITING` + position, ou `ADMITTED` |
| `queue/QueueAdmissionJob` | Le tick, sur chaque instance ; un verrou `SET NX PX` fait qu'une seule agit par tick |

- Tout est en O(1), quelle que soit la taille de la file : aucune liste à parcourir.
- Le curseur ne dépasse jamais le dernier numéro tiré : pendant les ticks à vide, personne n'est admis d'avance.
- Le ticket est **consommé** à la réservation (vérification et suppression dans un même script Lua) : impossible de le prêter ou de l'utiliser deux fois. Le contrôle d'idempotence passe **avant** : un retry avec la même clé est rejoué même si le ticket a déjà servi.
- Activée par `flashsale.queue.enabled=true`, et seulement avec Redis.

### Étape 7 : observabilité et CI

- **Micrometer** : `flashsale.booking` (timer par stratégie et résultat), `flashsale.holds` (par issue), `flashsale.confirmations`, `flashsale.holds.expired`, `flashsale.queue.joined`, `flashsale.queue.waiting`. Histogrammes activés pour calculer p95/p99 dans Prometheus, sur toutes les instances à la fois.
- `ops/` : configuration Prometheus, provisioning Grafana et dashboard « Flash Sale » (8 panneaux). Mode d'emploi dans `ops/README.md`. **Pas encore lancés sur ce poste** : il faut télécharger les ZIP.
- **CI GitHub Actions** (`.github/workflows/ci.yml`) : job `build` (tous les tests) ; job `load-test` avec un vrai Postgres 17 et un vrai Redis 7 en conteneurs, deux instances, des tirs Gatling `atomic` et `redis`, et un échec si la base n'est pas cohérente. Les deux instances démarrent ensemble : Flyway pose un verrou en base, une seule applique les migrations. **Pas encore exécutée** : elle tournera au premier push.
- `mvnw` est marqué exécutable dans git (il ne l'était pas, la CI Linux aurait échoué).
- **Le test naïf est maintenant vert** : il vérifie que la survente *se produit*. Un test volontairement rouge empêche toute CI ; inversé, il garde la démonstration et alerte si un jour la survente n'est plus reproduite.

### Tests

32 tests, tous verts, sans Postgres ni Redis à installer : `PostgresIntegrationTest` démarre le Postgres embarqué, `TestRedis` le serveur jedis-mock. Les jobs planifiés sont coupés en test (`flashsale.scheduling.enabled=false`) ; les tests les déclenchent eux-mêmes au moment voulu.

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

Les 7 étapes sont faites (sections 5 bis et 5 ter). Restent :

- **Lancer Prometheus et Grafana** en ZIP et vérifier le dashboard pendant un tir (`ops/README.md`).
- **Pousser la branche** et vérifier que la CI passe, en particulier le job `load-test` qui n'a jamais tourné.
- **Isoler les KO résiduels** des tirs Gatling (quelques centaines sur 10 000).
- Idées pour aller plus loin : stock découpé en plusieurs lignes (moins de contention sur la ligne chaude), pool Hikari plus grand pour retester les virtual threads, limiter les réservations par utilisateur.

**Question en attente :** si on ajoute `synchronized` sur `book()` de la version naïve, le test passe-t-il ? Pourquoi ça ne suffit pas en production ?

---

## 8. Phrases pour l'entretien

- « J'ai isolé chaque stratégie de verrouillage derrière une interface commune, ce qui m'a permis de les benchmarker avec exactement le même test de charge. »
- « Postgres est la source de vérité, Redis n'est qu'un accélérateur : s'il tombe, on le reconstruit depuis la base, sans risque de survente. »
- « Mes tests de concurrence tournent sur un vrai PostgreSQL, jamais sur H2, parce que la sémantique des verrous diffère d'un moteur à l'autre. »
- « J'ai choisi Java 25 parce que depuis le JDK 24, `synchronized` ne bloque plus les virtual threads sur leur thread porteur. »
- « Je désactive l'open-in-view pour que chaque connexion à la base soit rendue dès la fin de la transaction. »
- « Un compteur de stock décrémenté atomiquement garantit l'absence de survente, au prix d'une ligne chaude. C'est un compromis entre correction et débit, et je l'ai mesuré. »
- « J'ai testé Redis en panne et Redis qui se trompe : dans les deux cas, 500 places vendues, pas une de plus. »
- « L'idempotence repose sur un index unique en base, pas sur un cache : elle tient même quand deux instances reçoivent le même double clic au même moment. »
- « Les virtual threads n'ont pas amélioré le débit : le goulot était le pool de connexions. Ajouter de la concurrence devant un goulot ne fait qu'allonger la file. »
- « Ma file d'attente est un ticket de boulangerie dans Redis : un INCR pour le numéro, un curseur pour les admissions, tout en O(1). »
