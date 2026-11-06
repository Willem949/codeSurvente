import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { OversellDemo } from './oversell-demo';

describe('OversellDemo', () => {
  let fixture: ComponentFixture<OversellDemo>;
  let http: HttpTestingController;

  const settle = () => new Promise((resolve) => setTimeout(resolve));
  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';
  const clickRun = () => (fixture.nativeElement as HTMLElement).querySelector('button')!.click();

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [OversellDemo],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(OversellDemo);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('affiche la survente de la version naïve et aucune pour les autres, d’après la base', async () => {
    clickRun();
    const resets: (string | null)[] = [];
    let strategy = '';
    for (let finished = false; !finished; ) {
      await settle();
      for (const reset of http.match((r) => r.url === '/bench/events/1/reset')) {
        resets.push(reset.request.params.get('seats'));
        reset.flush(null, { status: 204, statusText: 'No Content' });
        finished = reset.request.params.get('seats') === '500';
      }
      // flush relance les requêtes suivantes de façon synchrone : on répond jusqu'à épuisement
      for (let pending = http.match((r) => r.url === '/bench/events/1/bookings'); pending.length; ) {
        for (const booking of pending) {
          strategy = booking.request.params.get('strategy')!;
          booking.flush({});
        }
        pending = http.match((r) => r.url === '/bench/events/1/bookings');
      }
      await settle();
      for (const check of http.match('/bench/events/1/check')) {
        const naive = strategy === 'naive';
        check.flush({
          totalSeats: 100,
          availableSeats: naive ? 37 : 0,
          reservations: naive ? 1000 : 100,
          oversold: naive ? 900 : 0,
          consistent: !naive,
        });
      }
    }
    await settle();
    fixture.detectChanges();

    expect(resets).toEqual(['100', '100', '100', '100', '100', '500']);
    const rows = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid=demo-row]'));
    expect(rows).toHaveLength(5);
    expect(rows[0].textContent).toContain('900 places en trop');
    expect(rows[0].textContent).toContain('1000 réservations en base pour 100 places');
    rows.slice(1).forEach((row) => expect(row.textContent).toContain('Aucune survente'));
    expect(text()).toContain('Lancer la démonstration');
  });

  it('sur une autre erreur, annule la mesure et remet le stock normal', async () => {
    clickRun();
    await settle();
    http.expectOne((r) => r.url === '/bench/events/1/reset').flush(null, { status: 500, statusText: 'Server Error' });
    await settle();
    const restore = http.expectOne((r) => r.url === '/bench/events/1/reset');
    expect(restore.request.params.get('seats')).toBe('500');
    restore.flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    fixture.detectChanges();
    expect(text()).toContain('les chiffres ci-dessous ne valent rien');
    expect(text()).not.toContain('Vente en cours');
  });

  it('explique quoi faire si l’API n’a pas le profil bench', async () => {
    clickRun();
    await settle();
    http.expectOne((r) => r.url === '/bench/events/1/reset').flush(null, { status: 404, statusText: 'Not Found' });
    await settle();
    fixture.detectChanges();
    expect(text()).toContain('profil "bench"');
  });
});
