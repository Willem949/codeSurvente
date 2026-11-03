# Observabilité locale : Prometheus et Grafana sans Docker

Les deux outils existent en ZIP pour Windows et se lancent sans droits administrateur, comme PostgreSQL.
Vérifié sur ce poste avec Prometheus 3.15.1 et Grafana 13.3.0.

## Installation (une fois)

1. Télécharger les ZIP Windows officiels : [Prometheus](https://github.com/prometheus/prometheus/releases) (`prometheus-<version>.windows-amd64.zip`) et [Grafana OSS](https://grafana.com/grafana/download?platform=windows) (`grafana-<version>.windows-amd64.zip`).
2. Vérifier leur SHA-256 (`Get-FileHash fichier.zip`) avec celui publié à côté du téléchargement.
3. Extraire pour obtenir `%USERPROFILE%\tools\prometheus\prometheus.exe` et `%USERPROFILE%\tools\grafana\bin\grafana.exe`.

## Lancement

```powershell
.\scripts\start-instances.ps1 -Count 3 -Redis -Queue    # instances sur 8081, 8082, 8083
.\scripts\observability.ps1                             # Prometheus + Grafana
# ... lancer un tir Gatling et regarder le dashboard ...
.\scripts\observability.ps1 -Stop
```

- Prometheus : http://localhost:9090/targets (une cible par instance ; les ports sans instance apparaissent `down`, c'est normal).
- Grafana : http://localhost:3000/d/flashsale (admin / admin). La source Prometheus et le dashboard « Flash Sale » sont créés automatiquement depuis `ops/grafana/provisioning`.
- Les deux outils n'écoutent que sur `127.0.0.1` : écouter sur toutes les interfaces fait apparaître la fenêtre du pare-feu Windows, qui demande les droits administrateur.
- **Le premier démarrage de Grafana prend plusieurs minutes** (enregistrement des plugins, ralenti par l'antivirus). Les suivants sont rapides.
- Si le projet n'est pas dans `C:\Users\wkraiem\Documents\codeSurvente`, corriger le chemin dans `ops/grafana/provisioning/dashboards/dashboards.yml`.

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

Chaque requête du dashboard a été vérifiée contre Prometheus après un tir de benchmark et un tir du parcours acheteur : les 12 requêtes des 8 panneaux renvoient des données.
