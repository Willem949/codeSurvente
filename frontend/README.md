# Front Angular

Une seule page : le stock en direct, puis le parcours d'achat (file d'attente, place bloquée avec compte à rebours, paiement simulé).

Il faut Node.js 24. Sans droits admin, l'archive ZIP de nodejs.org suffit : la décompresser, puis ajouter le dossier au `Path` de la session.

```powershell
npm install        # une seule fois
npm start          # http://127.0.0.1:4200, l'API doit tourner (voir le README principal)
npm test -- --watch=false
npm run build      # dans dist/frontend
```

`npm start` envoie `/events` et `/reservations` vers `http://127.0.0.1:8081` (`proxy.conf.mjs`). Pour viser une autre instance, définir `$env:API_URL` avant de lancer.

Le code tient dans `src/app` : `flash-sale-api.ts` (un appel par endpoint) et `app.ts` (le parcours).
