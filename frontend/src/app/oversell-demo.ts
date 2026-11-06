import { Component, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, firstValueFrom, from, lastValueFrom, map, mergeMap, of, tap } from 'rxjs';
import { BenchCheck, FlashSaleApi } from './flash-sale-api';

const EVENT_ID = 1;
const SEATS = 100;
const ATTEMPTS = 1000;
/** Un navigateur n'ouvre pas plus de 6 connexions par serveur en HTTP/1.1 */
const CONCURRENCY = 6;
/** Le stock de la vente normale, remis à la fin de la démonstration */
const NORMAL_SEATS = 500;

const STRATEGIES = [
  { id: 'naive', label: 'Naïve (le code de départ)' },
  { id: 'optimistic', label: 'Verrou optimiste' },
  { id: 'pessimistic', label: 'Verrou pessimiste' },
  { id: 'atomic', label: 'UPDATE atomique' },
  { id: 'check', label: 'Contrainte CHECK' },
];

interface Row {
  id: string;
  label: string;
  done: number;
  accepted: number;
  check: BenchCheck | null;
}

/** Même rafale sur chaque stratégie, puis on demande à la base combien de places sont parties. */
@Component({
  selector: 'app-oversell-demo',
  styleUrl: './oversell-demo.css',
  templateUrl: './oversell-demo.html',
})
export class OversellDemo {
  private readonly api = inject(FlashSaleApi);

  protected readonly seats = SEATS;
  protected readonly attempts = ATTEMPTS;
  protected readonly rows = signal<Row[]>([]);
  protected readonly running = signal(false);
  protected readonly error = signal('');

  protected async run() {
    if (this.running()) {
      return;
    }
    this.running.set(true);
    this.error.set('');
    this.rows.set(STRATEGIES.map((s) => ({ ...s, done: 0, accepted: 0, check: null })));
    try {
      for (const strategy of STRATEGIES) {
        await this.fire(strategy.id);
      }
      await firstValueFrom(this.api.benchReset(EVENT_ID, NORMAL_SEATS));
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 404) {
        this.error.set('Démonstration impossible : il faut une API lancée avec le profil "bench" (scripts/start-instances.ps1).');
      } else {
        this.error.set('Démonstration interrompue par une erreur de l\'API : les chiffres ci-dessous ne valent rien, relancez-la.');
        await firstValueFrom(this.api.benchReset(EVENT_ID, NORMAL_SEATS)).catch(() => undefined);
      }
    } finally {
      this.running.set(false);
    }
  }

  /** Part de la ligne en vert (places qui existent) quand la base en a vendu plus qu'il n'y en a */
  protected percent(row: Row, seats: number) {
    const sold = row.check?.reservations ?? 0;
    return (100 * seats) / Math.max(sold, SEATS);
  }

  private async fire(strategy: string) {
    await firstValueFrom(this.api.benchReset(EVENT_ID, SEATS));
    const buyers = Array.from({ length: ATTEMPTS }, (_, i) => `demo-${strategy}-${i}`);
    await lastValueFrom(
      from(buyers).pipe(
        mergeMap(
          (buyer) =>
            this.api.benchBook(EVENT_ID, strategy, buyer).pipe(
              map(() => true),
              // 409 (complet) ou 503 (trop de conflits) : l'acheteur repart sans place, c'est normal
              catchError(() => of(false)),
            ),
          CONCURRENCY,
        ),
        tap((accepted) => this.update(strategy, (row) => ({
          ...row,
          done: row.done + 1,
          accepted: row.accepted + (accepted ? 1 : 0),
        }))),
      ),
    );
    const check = await firstValueFrom(this.api.benchCheck(EVENT_ID));
    this.update(strategy, (row) => ({ ...row, check }));
  }

  private update(id: string, change: (row: Row) => Row) {
    this.rows.update((rows) => rows.map((row) => (row.id === id ? change(row) : row)));
  }
}
