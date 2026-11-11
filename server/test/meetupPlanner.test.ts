import { describe, expect, it } from 'vitest';
import { chooseMeetAt, choosePlace, travelMinutes } from '../src/modules/meetupPlanner.js';

const gangnam = { latitude: 37.4979, longitude: 127.0276 };
const pangyo = { latitude: 37.3948, longitude: 127.1112 };
const place = (id: string, latitude: number, longitude: number) => ({ id, name: id, latitude, longitude });
const at = (iso: string) => new Date(iso);

describe('이동 시간', () => {
  it('30km/h 기준에 5분을 더해 올림한다', () => {
    expect(travelMinutes(gangnam, gangnam)).toBe(5);
    // 강남역-판교 약 13.5km → 27분 + 5분
    expect(travelMinutes(gangnam, pangyo)).toBe(33);
  });
});

describe('장소 고르기', () => {
  it('더 오래 걸리는 쪽이 가장 덜 걸리는 곳을 고른다', () => {
    const places = [place('gangnam', 37.4985, 127.028), place('pangyo', 37.3947, 127.111), place('suseo', 37.4873, 127.1017)];
    const choice = choosePlace(gangnam, pangyo, places)!;
    expect(choice.place.id).toBe('suseo');
    expect(Math.max(choice.userTravelMinutes, choice.peerTravelMinutes)).toBeLessThan(travelMinutes(gangnam, pangyo));
  });

  it('더 오래 걸리는 시간이 같으면 합이 작은 곳', () => {
    const user = { latitude: 37.5, longitude: 127.0 };
    const peer = { latitude: 37.5, longitude: 127.2 };
    const even = place('even', 37.57, 127.1);
    const lopsided = place('lopsided', 37.5, 127.066);
    expect([travelMinutes(user, even), travelMinutes(peer, even)]).toEqual([29, 29]);
    expect([travelMinutes(user, lopsided), travelMinutes(peer, lopsided)]).toEqual([17, 29]);
    expect(choosePlace(user, peer, [even, lopsided])!.place.id).toBe('lopsided');
  });

  it('후보가 없으면 null', () => {
    expect(choosePlace(gangnam, pangyo, [])).toBeNull();
  });
});

describe('시각 정하기', () => {
  const base = { now: at('2026-11-05T09:03:00Z'), freeFrom: at('2026-11-05T09:00:00Z'), freeUntil: at('2026-11-05T15:00:00Z'), busy: [] };

  it('지금 + 더 오래 걸리는 이동 시간을 10분 단위로 올린다', () => {
    expect(chooseMeetAt({ ...base, travelMinutes: 25 })).toEqual(at('2026-11-05T09:30:00Z'));
    expect(chooseMeetAt({ ...base, travelMinutes: 27 })).toEqual(at('2026-11-05T09:30:00Z'));
    expect(chooseMeetAt({ ...base, travelMinutes: 28 })).toEqual(at('2026-11-05T09:40:00Z'));
  });

  it('원하는 시각과 일행이 비기 시작하는 시각 중 늦은 쪽을 따른다', () => {
    expect(chooseMeetAt({ ...base, travelMinutes: 10, preferredAt: at('2026-11-05T11:05:00Z') })).toEqual(at('2026-11-05T11:10:00Z'));
    expect(chooseMeetAt({ ...base, travelMinutes: 10, freeFrom: at('2026-11-05T12:00:00Z') })).toEqual(at('2026-11-05T12:00:00Z'));
  });

  it('앞뒤 90분 안에 다른 예약이 있으면 30분씩 미룬다', () => {
    // 10:00 예약이 있으면 09:30, 10:00, 10:30, 11:00은 겹치고 11:30에 잡힌다
    expect(chooseMeetAt({ ...base, travelMinutes: 25, busy: [at('2026-11-05T10:00:00Z')] })).toEqual(at('2026-11-05T11:30:00Z'));
  });

  it('일행이 비는 시간을 넘거나 원하는 시각이 그 밖이면 null', () => {
    expect(chooseMeetAt({ ...base, travelMinutes: 25, freeUntil: at('2026-11-05T09:20:00Z') })).toBeNull();
    expect(chooseMeetAt({ ...base, travelMinutes: 25, preferredAt: at('2026-11-05T16:00:00Z') })).toBeNull();
    expect(chooseMeetAt({ ...base, travelMinutes: 25, preferredAt: at('2026-11-05T08:00:00Z') })).toBeNull();
    expect(chooseMeetAt({ ...base, travelMinutes: 25, freeUntil: at('2026-11-05T11:00:00Z'), busy: [at('2026-11-05T10:00:00Z')] })).toBeNull();
  });
});
