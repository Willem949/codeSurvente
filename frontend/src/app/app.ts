import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, catchError, firstValueFrom, interval, of, startWith, switchMap } from 'rxjs';
import { EventInfo, FlashSaleApi, QueueTicket, Reservation } from './flash-sale-api';
import { OversellDemo } from './oversell-demo';

const EVENT_ID = 1;
/** Nombre d'essais sur un 503 (Redis surchargé) */
const MAX_ATTEMPTS = 5;

type Step = 'idle' | 'queued' | 'holding' | 'held' | 'confirming' | 'confirmed' | 'failed';

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

@Component({
  imports: [OversellDemo],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  private readonly api = inject(FlashSaleApi);

  protected readonly event = signal<EventInfo | null>(null);
  protected readonly userId = signal(`acheteur-${Math.random().toString(36).slice(2, 8)}`);
  protected readonly step = signal<Step>('idle');
  protected readonly position = signal(0);
  protected readonly reservation = signal<Reservation | null>(null);
  protected readonly message = signal('');

  private readonly now = signal(Date.now());
  /** Secondes restantes avant que la place soit remise en vente */
  protected readonly secondsLeft = computed(() => {
    const reservation = this.reservation();
    if (!reservation) {
      return 0;
    }
    return Math.max(0, Math.ceil((Date.parse(reservation.expiresAt) - this.now()) / 1000));
  });
  protected readonly countdown = computed(() => {
    const s = this.secondsLeft();
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  });
  protected readonly soldPercent = computed(() => {
    const event = this.event();
    return event ? Math.round((100 * (event.totalSeats - event.availableSeats)) / event.totalSeats) : 0;
  });

  constructor() {
    // Le stock affiché est rafraîchi toutes les 2 s : on voit les places partir pendant un tir Gatling
    interval(2000)
      .pipe(
        startWith(0),
        switchMap(() => this.api.event(EVENT_ID).pipe(catchError(() => of(null)))),
        takeUntilDestroyed(),
      )
      .subscribe((event) => this.event.set(event));

    const clock = setInterval(() => this.now.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(clock));
  }

  protected setUserId(value: string) {
    this.userId.set(value.trim());
  }

  protected async buy() {
    // Double clic : le 2e clic partirait avec une autre clé et le serveur répondrait « déjà réservé »,
    // alors que l'acheteur a bien sa place. Le test porte sur l'état, le bouton n'a pas encore disparu
    if (this.step() !== 'idle') {
      return;
    }
    this.step.set('holding');
    this.message.set('');
    this.reservation.set(null);
    try {
      const queueToken = await this.waitForAdmission();
      await this.hold(queueToken);
    } catch (error) {
      this.fail(error);
    }
  }

  protected async confirm() {
    const reservation = this.reservation();
    if (!reservation) {
      return;
    }
    this.step.set('confirming');
    try {
      await this.retryIfUnavailable(() => this.api.confirm(reservation.id));
      this.step.set('confirmed');
    } catch (error) {
      this.fail(error);
    }
  }

  protected reset() {
    this.step.set('idle');
    this.reservation.set(null);
    this.message.set('');
  }

  /** Renvoie le ticket une fois admis, ou undefined si la file d'attente est désactivée côté serveur. */
  private async waitForAdmission(): Promise<string | undefined> {
    let ticket: QueueTicket;
    try {
      ticket = await this.retryIfUnavailable(() => this.api.joinQueue(EVENT_ID));
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 404) {
        return undefined;
      }
      throw error;
    }
    this.step.set('queued');
    while (ticket.status !== 'ADMITTED') {
      this.position.set(ticket.position);
      await sleep(1000);
      const token = ticket.token;
      ticket = await this.retryIfUnavailable(() => this.api.queueStatus(EVENT_ID, token));
    }
    return ticket.token;
  }

  private async hold(queueToken: string | undefined) {
    this.step.set('holding');
    // Une seule clé pour tous les essais : si le premier a réussi mais que la réponse s'est perdue,
    // le serveur renvoie la même réservation au lieu d'en créer une deuxième
    const idempotencyKey = crypto.randomUUID();
    const reservation = await this.retryIfUnavailable(() =>
      this.api.hold(EVENT_ID, this.userId(), idempotencyKey, queueToken),
    );
    this.reservation.set(reservation);
    this.step.set('held');
  }

  /** Un 503 veut dire « réessayez » : on attend le délai demandé par le serveur (Retry-After). */
  private async retryIfUnavailable<T>(call: () => Observable<T>): Promise<T> {
    for (let attempt = 1; ; attempt++) {
      try {
        const result = await firstValueFrom(call());
        this.message.set('');
        return result;
      } catch (error) {
        if (!(error instanceof HttpErrorResponse) || error.status !== 503 || attempt === MAX_ATTEMPTS) {
          throw error;
        }
        const retryAfter = Number(error.headers.get('Retry-After') ?? 1);
        this.message.set(`Service saturé, nouvel essai dans ${retryAfter} s (essai ${attempt + 1}/${MAX_ATTEMPTS})`);
        await sleep(retryAfter * 1000);
      }
    }
  }

  private fail(error: unknown) {
    this.step.set('failed');
    if (!(error instanceof HttpErrorResponse)) {
      this.message.set('Erreur inattendue');
      return;
    }
    // Les erreurs de l'API sont au format RFC 9457 : le texte à afficher est dans "detail"
    const detail = error.error?.detail;
    if (detail) {
      this.message.set(detail);
    } else if (error.status === 404 && this.reservation()) {
      this.message.set('Réservation introuvable');
    } else if (error.status === 404) {
      this.message.set("Ticket de file d'attente expiré, recommencez");
    } else if (error.status === 0 || error.status >= 500) {
      this.message.set("L'API ne répond pas");
    } else {
      this.message.set(`Erreur ${error.status}`);
    }
  }
}
