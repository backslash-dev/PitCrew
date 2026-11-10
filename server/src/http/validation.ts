import { z } from 'zod';

const MOBILE = /^01[016789]-?\d{3,4}-?\d{4}$/;

/** 휴대폰 번호를 010-1234-5678 형태로 맞춘다. 앱과 같은 규칙. */
export const phoneSchema = z
  .string()
  .trim()
  .regex(MOBILE, '휴대폰 번호 형식이 아닙니다')
  .transform((value) => {
    const digits = value.replace(/\D/g, '');
    const middle = digits.length - 7;
    return `${digits.slice(0, 3)}-${digits.slice(3, 3 + middle)}-${digits.slice(-4)}`;
  });

export const idSchema = z.uuid();

/**
 * 오프셋이 있는 ISO-8601만 받는다. 기기마다 시간대가 달라 오프셋 없는 시각은 모호하다.
 * DB 컬럼이 초 단위라 밀리초는 버린다. 그대로 두면 저장 때 반올림돼 응답과 조회 값이 달라진다.
 */
export const instantSchema = z.iso.datetime({ offset: true }).transform((value) => new Date(Math.floor(Date.parse(value) / 1000) * 1000));
