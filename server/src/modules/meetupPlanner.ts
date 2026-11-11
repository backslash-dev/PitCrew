export interface Point {
  latitude: number;
  longitude: number;
}

export interface Place extends Point {
  id: string;
  name: string;
}

// 위치를 모르면 강남역에서 출발한다고 본다
export const DEFAULT_ORIGIN: Point = { latitude: 37.4979, longitude: 127.0276 };

const AVERAGE_KMH = 30;
const OVERHEAD_MINUTES = 5;
const ROUND_MS = 10 * 60_000;
const PUSH_MS = 30 * 60_000;
export const BUFFER_MINUTES = 90;

export function distanceKm(a: Point, b: Point): number {
  const rad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = rad(b.latitude - a.latitude);
  const dLon = rad(b.longitude - a.longitude);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.latitude)) * Math.cos(rad(b.latitude)) * Math.sin(dLon / 2) ** 2;
  return 2 * 6371 * Math.asin(Math.sqrt(h));
}

/** 도심 평균 속도에 주차·도보 시간을 더한 근사치다. */
export function travelMinutes(a: Point, b: Point): number {
  return Math.ceil((distanceKm(a, b) / AVERAGE_KMH) * 60 + OVERHEAD_MINUTES);
}

export interface PlaceChoice<P extends Place> {
  place: P;
  userTravelMinutes: number;
  peerTravelMinutes: number;
}

export function choosePlace<P extends Place>(user: Point, peer: Point, places: readonly P[]): PlaceChoice<P> | null {
  let best: PlaceChoice<P> | null = null;
  for (const place of places) {
    const candidate = { place, userTravelMinutes: travelMinutes(user, place), peerTravelMinutes: travelMinutes(peer, place) };
    if (best === null || compare(candidate, best) < 0) best = candidate;
  }
  return best;
}

function compare(a: PlaceChoice<Place>, b: PlaceChoice<Place>): number {
  const longer = Math.max(a.userTravelMinutes, a.peerTravelMinutes) - Math.max(b.userTravelMinutes, b.peerTravelMinutes);
  if (longer !== 0) return longer;
  return a.userTravelMinutes + a.peerTravelMinutes - (b.userTravelMinutes + b.peerTravelMinutes);
}

export interface MeetTimeInput {
  now: Date;
  preferredAt?: Date | undefined;
  travelMinutes: number;
  freeFrom: Date;
  freeUntil: Date;
  busy: readonly Date[];
}

export function chooseMeetAt(input: MeetTimeInput): Date | null {
  const { preferredAt, freeFrom, freeUntil } = input;
  if (preferredAt && (preferredAt < freeFrom || preferredAt > freeUntil)) return null;
  const earliest = Math.max(preferredAt?.getTime() ?? 0, input.now.getTime() + input.travelMinutes * 60_000, freeFrom.getTime());
  let at = Math.ceil(earliest / ROUND_MS) * ROUND_MS;
  const buffer = BUFFER_MINUTES * 60_000;
  while (at <= freeUntil.getTime() && input.busy.some((b) => Math.abs(b.getTime() - at) < buffer)) at += PUSH_MS;
  return at <= freeUntil.getTime() ? new Date(at) : null;
}
