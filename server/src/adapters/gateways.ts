import { randomUUID } from 'node:crypto';

export type SendResult = { ok: true; messageId: string } | { ok: false; error: string };

/** 알림 발송. 실제 SMS·알림톡은 발신번호 사전등록 뒤 이 인터페이스로 붙인다. */
export interface Notifier {
  readonly provider: string;
  send(message: { name: string; phone: string; text: string }): Promise<SendResult>;
}

export type GatewayResult = { accepted: true } | { accepted: false; reason: string };

/** 식당 예약 시스템. 제휴 전까지 목업. */
export interface RestaurantGateway {
  readonly provider: string;
  requestReschedule(request: { placeName: string; from: Date; to: Date; partySize: number }): Promise<GatewayResult>;
}

/** 매장 주문 시스템. 제휴 전까지 목업. */
export interface StoreOrderGateway {
  readonly provider: string;
  placeOrder(request: { storeName: string; item: string; pickupAt: Date; prepMinutes: number }): Promise<GatewayResult>;
}

export class MockNotifier implements Notifier {
  readonly provider = 'mock';
  async send(): Promise<SendResult> {
    return { ok: true, messageId: `mock-${randomUUID()}` };
  }
}

export class MockRestaurantGateway implements RestaurantGateway {
  readonly provider = 'mock';
  async requestReschedule(request: { from: Date; to: Date }): Promise<GatewayResult> {
    if (request.to.getTime() <= request.from.getTime()) return { accepted: false, reason: '앞당기는 변경은 받지 않습니다' };
    return { accepted: true };
  }
}

export class MockStoreOrderGateway implements StoreOrderGateway {
  readonly provider = 'mock';
  constructor(private readonly now: () => Date = () => new Date()) {}

  async placeOrder(request: { pickupAt: Date; prepMinutes: number }): Promise<GatewayResult> {
    const earliest = this.now().getTime() + request.prepMinutes * 60_000;
    if (request.pickupAt.getTime() < earliest) return { accepted: false, reason: '조리 시간보다 이른 픽업은 받을 수 없습니다' };
    return { accepted: true };
  }
}

export interface Gateways {
  notifier: Notifier;
  restaurants: RestaurantGateway;
  storeOrders: StoreOrderGateway;
}

export const mockGateways = (now?: () => Date): Gateways => ({
  notifier: new MockNotifier(),
  restaurants: new MockRestaurantGateway(),
  storeOrders: new MockStoreOrderGateway(now),
});
