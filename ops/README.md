# Observabilité locale : Prometheus et Grafana sans Docker

Les deux outils existent en ZIP pour Windows et se lancent sans droits administrateur, comme PostgreSQL.

1. Télécharger les ZIP (prometheus.io/download, grafana.com/grafana/download, édition OSS, Windows) et les extraire dans `%USERPROFILE%\tools`.
2. Lancer une ou plusieurs instances de l'application :
   ```powershell
   .\scripts\start-instances.ps1 -Count 3 -Redis -Queue    # ports 8081, 8082, 8083
   ```
3. Lancer Prometheus, qui interroge `/actuator/prometheus` sur chaque instance toutes les 5 secondes :
   ```powershell
   & "$env:USERPROFILE\tools\prometheus\prometheus.exe" --config.file=ops\prometheus\prometheus.yml
   ```
   Vérifier sur http://localhost:9090/targets que les instances sont `UP`.
4. Lancer Grafana avec le provisioning du projet (source Prometheus et dashboard « Flash Sale » créés automatiquement) :
   ```powershell
   $env:GF_PATHS_PROVISIONING = "$PWD\ops\grafana\provisioning"
   & "$env:USERPROFILE\tools\grafana\bin\grafana-server.exe" --homepath "$env:USERPROFILE\tools\grafana"
   ```
   Ouvrir http://localhost:3000 (admin / admin). Si le projet n'est pas dans `C:\Users\wkraiem\Documents\codeSurvente`,
   corriger le chemin dans `ops/grafana/provisioning/dashboards/dashboards.yml`.
5. Lancer un tir Gatling et regarder le dashboard bouger.

## Ce que montre le dashboard

| Panneau | Métrique | Ce qu'il faut y lire |
|---|---|---|
| Débit HTTP | `http_server_requests_seconds_count` | 201 = place obtenue, 409 = complet, 5xx = vrai problème |
| Latence p99 | `http_server_requests_seconds_bucket` | Calculée par Prometheus à partir des histogrammes, sur toutes les instances |
| Benchmark par stratégie | `flashsale_booking_seconds` | Débit et p99 de chaque stratégie (endpoints `/bench`) |
| Pré-réservations | `flashsale_holds_total`, `flashsale_confirmations_total`, `flashsale_holds_expired_total` | `REPLAYED` = double clic ou retry absorbé par l'idempotence |
| File d'attente | `flashsale_queue_waiting`, `flashsale_queue_joined_total` | Combien de personnes attendent, à quel rythme elles arrivent |
| Pool Postgres | `hikaricp_connections_active` / `pending` | Des threads en attente de connexion = la base est le goulot |
| Threads JVM | `jvm_threads_live_threads` | Avec les virtual threads, reste bas même sous forte charge |
