import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from './app';

describe('App : le parcours d’un acheteur', () => {
  let fixture: ComponentFixture<App>;
  let http: HttpTestingController;

  const concert = { id: 1, name: 'Concert', totalSeats: 500, availableSeats: 120 };

  /** Laisse les promesses et les timers avancer, répond au rafraîchissement du stock, puis met la page à jour */
  async function advance(ms = 0) {
    await vi.advanceTimersByTimeAsync(ms);
    // switchMap annule un rafraîchissement resté sans réponse quand le suivant part
    http.match('/events/1').filter((poll) => !poll.cancelled).forEach((poll) => poll.flush(concert));
    fixture.detectChanges();
  }

  function text() {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function click(label: string) {
    const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'));
    const button = buttons.find((b) => b.textContent?.includes(label));
    if (!button) {
      throw new Error(`Bouton « ${label} » introuvable`);
    }
    button.click();
  }

  function reservation(id: number) {
    return {
      id,
      eventId: 1,
      userId: 'alice',
      status: 'PENDING',
      expiresAt: new Date(Date.now() + 600_000).toISOString(),
    };
  }

  const queueDisabled = { status: 404, statusText: 'Not Found' };

  beforeEach(async () => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await advance();
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('affiche le stock restant', () => {
    expect(text()).toContain('120 places restantes sur 500');
  });

  it('sans file d’attente : bloque une place, affiche le délai, puis confirme', async () => {
    click('Réserver');
    await advance();
    http.expectOne({ method: 'POST', url: '/events/1/queue' }).flush(null, queueDisabled);
    await advance();

    const hold = http.expectOne({ method: 'POST', url: '/events/1/reservations' });
    expect(hold.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(hold.request.headers.has('X-Queue-Token')).toBe(false);
    expect(hold.request.body.userId).toMatch(/^acheteur-/);
    hold.flush(reservation(7), { status: 201, statusText: 'Created' });
    await advance();
    expect(text()).toContain('réservation n°7');
    expect(text()).toContain('10:00');

    await advance(61_000);
    expect(text()).toContain('8:59');

    click('Payer');
    await advance();
    http.expectOne({ method: 'POST', url: '/reservations/7/confirm' }).flush(null, { status: 204, statusText: 'No Content' });
    await advance();
    expect(text()).toContain('est à vous');
  });

  it('avec file d’attente : attend son tour, puis réserve avec le ticket', async () => {
    click('Réserver');
    await advance();
    http.expectOne({ method: 'POST', url: '/events/1/queue' })
      .flush({ token: 't1', status: 'WAITING', position: 3 }, { status: 201, statusText: 'Created' });
    await advance();
    expect(text()).toContain('file d\'attente');
    expect(text()).toContain('3');

    await advance(1000);
    http.expectOne('/events/1/queue/t1').flush({ token: 't1', status: 'ADMITTED', position: 0 });
    await advance();

    const hold = http.expectOne({ method: 'POST', url: '/events/1/reservations' });
    expect(hold.request.headers.get('X-Queue-Token')).toBe('t1');
    hold.flush(reservation(8), { status: 201, statusText: 'Created' });
    await advance();
    expect(text()).toContain('réservation n°8');
  });

  it('sur un 503, réessaie après Retry-After avec la même Idempotency-Key', async () => {
    click('Réserver');
    await advance();
    http.expectOne({ method: 'POST', url: '/events/1/queue' }).flush(null, queueDisabled);
    await advance();

    const first = http.expectOne({ method: 'POST', url: '/events/1/reservations' });
    first.flush({ detail: 'Service temporairement indisponible' }, {
      status: 503,
      statusText: 'Service Unavailable',
      headers: { 'Retry-After': '1' },
    });
    await advance();
    expect(text()).toContain('nouvel essai dans 1 s');

    await advance(1000);
    const second = http.expectOne({ method: 'POST', url: '/events/1/reservations' });
    expect(second.request.headers.get('Idempotency-Key')).toBe(first.request.headers.get('Idempotency-Key'));
    second.flush(reservation(9), { status: 201, statusText: 'Created' });
    await advance();
    expect(text()).toContain('réservation n°9');
  });

  it('un double clic ne lance qu’un seul parcours', async () => {
    click('Réserver');
    click('Réserver');
    await advance();
    http.expectOne({ method: 'POST', url: '/events/1/queue' }).flush(null, queueDisabled);
    await advance();
    http.expectOne({ method: 'POST', url: '/events/1/reservations' })
      .flush(reservation(12), { status: 201, statusText: 'Created' });
    await advance();
    expect(text()).toContain('réservation n°12');
  });

  it('sur un 503 en entrant dans la file, réessaie au lieu d’abandonner', async () => {
    click('Réserver');
    await advance();
    http.expectOne({ method: 'POST', url: '/events/1/queue' }).flush(
      { detail: 'Service temporairement indisponible' },
      { status: 503, statusText: 'Service Unavailable', headers: { 'Retry-After': '1' } },
    );
    await advance();
    expect(text()).toContain('nouvel essai dans 1 s');

    await advance(1000);
    http.expectOne({ method: 'POST', url: '/events/1/queue' })
      .flush({ token: 't2', status: 'ADMITTED', position: 0 }, { status: 201, statusText: 'Created' });
    await advance();
    expect(text()).not.toContain('nouvel essai');

    const hold = http.expectOne({ method: 'POST', url: '/events/1/reservations' });
    expect(hold.request.headers.get('X-Queue-Token')).toBe('t2');
    hold.flush(reservation(11), { status: 201, statusText: 'Created' });
    await advance();
    expect(text()).toContain('réservation n°11');
  });

  it('affiche le message du serveur quand c’est complet', async () => {
    click('Réserver');
    await advance();
    http.expectOne({ method: 'POST', url: '/events/1/queue' }).flush(null, queueDisabled);
    await advance();
    http.expectOne({ method: 'POST', url: '/events/1/reservations' })
      .flush({ status: 409, detail: 'Plus aucune place disponible' }, { status: 409, statusText: 'Conflict' });
    await advance();
    expect(text()).toContain('Plus aucune place disponible');

    click('Recommencer');
    await advance();
    expect(text()).toContain('Réserver une place');
  });

  it('affiche « 410 Gone » comme une expiration', async () => {
    click('Réserver');
    await advance();
    http.expectOne({ method: 'POST', url: '/events/1/queue' }).flush(null, queueDisabled);
    await advance();
    http.expectOne({ method: 'POST', url: '/events/1/reservations' })
      .flush(reservation(10), { status: 201, statusText: 'Created' });
    await advance();

    click('Payer');
    await advance();
    http.expectOne({ method: 'POST', url: '/reservations/10/confirm' }).flush(
      { status: 410, detail: 'Pré-réservation expirée, la place a été remise en vente' },
      { status: 410, statusText: 'Gone' },
    );
    await advance();
    expect(text()).toContain('remise en vente');
  });
});
