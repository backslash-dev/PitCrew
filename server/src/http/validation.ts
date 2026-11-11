import { z } from 'zod';

const MOBILE = /^01[016789]-?\d{3,4}-?\d{4}$/;

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

/** 시간대가 기기마다 달라 오프셋을 요구하고, DB 반올림을 피해 밀리초는 버린다. */
export const instantSchema = z.iso.datetime({ offset: true }).transform((value) => new Date(Math.floor(Date.parse(value) / 1000) * 1000));
