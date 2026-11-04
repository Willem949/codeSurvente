// En dev, le front et l'API sont servis depuis la même origine : pas de CORS à configurer côté Spring.
// Par défaut, l'instance de scripts/start-instances.ps1 ; API_URL permet d'en viser une autre
const target = process.env.API_URL ?? 'http://127.0.0.1:8081';

export default {
  '/events': { target, secure: false },
  '/reservations': { target, secure: false },
};
