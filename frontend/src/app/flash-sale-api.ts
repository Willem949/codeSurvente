import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';

export interface EventInfo {
  id: number;
  name: string;
  totalSeats: number;
  availableSeats: number;
}

export interface QueueTicket {
  token: string;
  status: 'WAITING' | 'ADMITTED';
  /** Nombre de personnes devant soi (0 une fois admis) */
  position: number;
}

export interface Reservation {
  id: number;
  eventId: number;
  userId: string;
  status: 'PENDING' | 'CONFIRMED' | 'EXPIRED';
  expiresAt: string;
}

export interface BenchCheck {
  totalSeats: number;
  availableSeats: number;
  reservations: number;
  oversold: number;
  consistent: boolean;
}

/** Les appels de l'API Spring, un par endpoint. */
@Injectable({ providedIn: 'root' })
export class FlashSaleApi {
  private readonly http = inject(HttpClient);

  event(eventId: number) {
    return this.http.get<EventInfo>(`/events/${eventId}`);
  }

  /** 404 si la file d'attente est désactivée (elle n'existe qu'avec Redis) */
  joinQueue(eventId: number) {
    return this.http.post<QueueTicket>(`/events/${eventId}/queue`, null);
  }

  queueStatus(eventId: number, token: string) {
    return this.http.get<QueueTicket>(`/events/${eventId}/queue/${token}`);
  }

  hold(eventId: number, userId: string, idempotencyKey: string, queueToken?: string) {
    let headers = new HttpHeaders({ 'Idempotency-Key': idempotencyKey });
    if (queueToken) {
      headers = headers.set('X-Queue-Token', queueToken);
    }
    return this.http.post<Reservation>(`/events/${eventId}/reservations`, { userId }, { headers });
  }

  confirm(reservationId: number) {
    return this.http.post<void>(`/reservations/${reservationId}/confirm`, null);
  }

  // Les endpoints de benchmark n'existent qu'avec le profil Spring "bench"

  /** Remet le stock à `seats` et efface toutes les réservations de l'événement */
  benchReset(eventId: number, seats: number) {
    return this.http.post<void>(`/bench/events/${eventId}/reset`, null, { params: { seats } });
  }

  benchBook(eventId: number, strategy: string, userId: string) {
    return this.http.post<unknown>(`/bench/events/${eventId}/bookings`, null, { params: { strategy, userId } });
  }

  /** Ce que contient vraiment la base : c'est elle qui fait foi, pas les réponses HTTP */
  benchCheck(eventId: number) {
    return this.http.get<BenchCheck>(`/bench/events/${eventId}/check`);
  }
}
