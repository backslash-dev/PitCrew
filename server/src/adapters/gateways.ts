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
  /** 직전에 받아들여진 변경을 무른다. 앞당기는 일반 변경과 달리 원래 잡혀 있던 자리로 돌아가는 요청이다. */
  revertReschedule(request: { placeName: string; current: Date; restoreTo: Date; partySize: number }): Promise<GatewayResult>;
}

/** 매장 주문 시스템. 제휴 전까지 목업. */
export interface StoreOrderGateway {
  readonly provider: string;
  placeOrder(request: { storeName: string; item: string; pickupAt: Date; prepMinutes: number }): Promise<GatewayResult>;
  cancelOrder(request: { storeName: string; item: string; placedAt: Date; pickupAt: Date }): Promise<GatewayResult>;
}

export type WaitlistResult = { accepted: true; position: number; waitMinutes: number } | { accepted: false; reason: string };

/** 식당 원격 줄서기. 제휴 전까지 목업. */
export interface WaitlistGateway {
  readonly provider: string;
  register(request: { restaurantName: string; partySize: number; expectedWaitMinutes: number }): Promise<WaitlistResult>;
  cancel(request: { restaurantName: string; registeredAt: Date }): Promise<GatewayResult>;
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

  async revertReschedule(): Promise<GatewayResult> {
    return { accepted: true };
  }
}

/** 조리를 시작하기 전(주문 후 [cancelWindowMinutes]분)까지만 취소를 받는다. */
export class MockStoreOrderGateway implements StoreOrderGateway {
  readonly provider = 'mock';
  constructor(
    private readonly now: () => Date = () => new Date(),
    private readonly cancelWindowMinutes = 5,
  ) {}

  async placeOrder(request: { pickupAt: Date; prepMinutes: number }): Promise<GatewayResult> {
    const earliest = this.now().getTime() + request.prepMinutes * 60_000;
    if (request.pickupAt.getTime() < earliest) return { accepted: false, reason: '조리 시간보다 이른 픽업은 받을 수 없습니다' };
    return { accepted: true };
  }

  async cancelOrder(request: { placedAt: Date }): Promise<GatewayResult> {
    if (this.now().getTime() > request.placedAt.getTime() + this.cancelWindowMinutes * 60_000) {
      return { accepted: false, reason: '매장에서 이미 조리를 시작해 취소할 수 없습니다' };
    }
    return { accepted: true };
  }
}

/** 등록 시 앞 팀 수는 예상 대기 시간을 팀당 5분으로 나눠 정한다. */
export class MockWaitlistGateway implements WaitlistGateway {
  readonly provider = 'mock';
  async register(request: { expectedWaitMinutes: number }): Promise<WaitlistResult> {
    const position = Math.max(1, Math.round(request.expectedWaitMinutes / 5));
    return { accepted: true, position, waitMinutes: request.expectedWaitMinutes };
  }

  async cancel(): Promise<GatewayResult> {
    return { accepted: true };
  }
}

export interface Gateways {
  notifier: Notifier;
  restaurants: RestaurantGateway;
  storeOrders: StoreOrderGateway;
  waitlists: WaitlistGateway;
}

export const mockGateways = (now?: () => Date): Gateways => ({
  notifier: new MockNotifier(),
  restaurants: new MockRestaurantGateway(),
  storeOrders: new MockStoreOrderGateway(now),
  waitlists: new MockWaitlistGateway(),
});
