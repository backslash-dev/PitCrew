import { randomUUID } from 'node:crypto';

export type SendResult = { ok: true; messageId: string } | { ok: false; error: string };

/** 실제 SMS·알림톡은 발신번호 사전등록 뒤 붙인다. */
export interface Notifier {
  readonly provider: string;
  send(message: { name: string; phone: string; text: string }): Promise<SendResult>;
}

export type GatewayResult = { accepted: true } | { accepted: false; reason: string };

export interface RestaurantGateway {
  readonly provider: string;
  requestReschedule(request: { placeName: string; from: Date; to: Date; partySize: number }): Promise<GatewayResult>;
  /** 앞당기는 일반 변경과 달리 원래 잡혀 있던 자리로 돌아가는 요청이다. */
  revertReschedule(request: { placeName: string; current: Date; restoreTo: Date; partySize: number }): Promise<GatewayResult>;
}

export interface StoreOrderGateway {
  readonly provider: string;
  placeOrder(request: { storeName: string; item: string; pickupAt: Date; prepMinutes: number }): Promise<GatewayResult>;
  cancelOrder(request: { storeName: string; item: string; placedAt: Date; pickupAt: Date }): Promise<GatewayResult>;
}

export type WaitlistResult = { accepted: true; position: number; waitMinutes: number } | { accepted: false; reason: string };

export interface WaitlistGateway {
  readonly provider: string;
  register(request: { restaurantName: string; partySize: number; expectedWaitMinutes: number }): Promise<WaitlistResult>;
  cancel(request: { restaurantName: string; registeredAt: Date }): Promise<GatewayResult>;
}

export interface PeerAgentGateway {
  readonly provider: string;
  confirm(request: { contactName: string; placeName: string; meetAt: Date }): Promise<GatewayResult>;
  cancel(request: { contactName: string; placeName: string; meetAt: Date }): Promise<GatewayResult>;
}

export type PeerReply = { replied: true } | { replied: false; reason: string };

/** 실제 구현은 답장 내용을 peer_profiles에 반영한 뒤 돌아와야 한다. */
export interface PeerReplyGateway {
  readonly provider: string;
  awaitReply(request: { contactName: string; phone: string }): Promise<PeerReply>;
}

/** 지금은 템플릿이고, LLM 구현이 붙으면 대체한다. */
export interface MailAssistant {
  readonly provider: string;
  summarize(mail: { senderName: string; subject: string; body: string }): Promise<string>;
  draftReply(request: { senderName: string; subject: string; body: string; instruction: string }): Promise<string>;
}

export interface Mailer {
  readonly provider: string;
  send(mail: { to: string; subject: string; body: string }): Promise<SendResult>;
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

/** cancelWindowMinutes는 매장이 조리를 시작하기 전까지의 시간이다. */
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

/** 앞 팀 수는 팀당 5분으로 어림한다. */
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

export class MockPeerAgentGateway implements PeerAgentGateway {
  readonly provider = 'mock';
  async confirm(): Promise<GatewayResult> {
    return { accepted: true };
  }

  async cancel(): Promise<GatewayResult> {
    return { accepted: true };
  }
}

/** 답장 내용은 시드가 넣어 둔 일행 정보를 쓴다. */
export class MockPeerReplyGateway implements PeerReplyGateway {
  readonly provider = 'mock';
  async awaitReply(): Promise<PeerReply> {
    return { replied: true };
  }
}

export const MAIL_SIGNATURE = '— 운전 중 PitCrew로 보냄';
const SUMMARY_MAX = 80;
const GREETING = /^(안녕하세요|안녕하십니까|수고\s*많으십니다|수고하십니다|좋은 아침)/;

export type ReplyIntent = 'accept' | 'decline' | 'late' | 'later' | 'other';

/** '괜찮지 않아'처럼 수락 말이 섞인 거절이 있어 거절을 먼저 본다. */
export function replyIntent(instruction: string): ReplyIntent {
  if (/어렵|안\s?돼|힘들|불가/.test(instruction)) return 'decline';
  if (/늦/.test(instruction)) return 'late';
  if (/나중에|이따|도착하고|운전\s?중/.test(instruction)) return 'later';
  if (/괜찮|좋아|그렇게|오케이|가능/.test(instruction)) return 'accept';
  return 'other';
}

const REPLY_TEMPLATES: Record<ReplyIntent, string> = {
  accept: '말씀하신 대로 진행해 주세요. 감사합니다.',
  decline: '말씀 주신 건은 이번에는 어려울 것 같습니다. 양해 부탁드립니다.',
  late: '조금 늦을 것 같습니다. 도착하는 대로 연락드리겠습니다.',
  later: '운전 중이라 도착하는 대로 자세히 답장드리겠습니다.',
  other: '메일 확인했습니다. 도착하는 대로 다시 연락드리겠습니다.',
};

export class MockMailAssistant implements MailAssistant {
  readonly provider = 'mock';
  async summarize(mail: { subject: string; body: string }): Promise<string> {
    const sentences = mail.body
      .split(/(?<=[.!?])\s+|\n+/)
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
    const first = sentences.find((s) => !GREETING.test(s));
    const text = first ? `${mail.subject.trim()}. ${first}` : mail.subject.trim();
    return text.length <= SUMMARY_MAX ? text : `${text.slice(0, SUMMARY_MAX - 1).trimEnd()}…`;
  }

  async draftReply(request: { senderName: string; instruction: string }): Promise<string> {
    return `${request.senderName}님, ${REPLY_TEMPLATES[replyIntent(request.instruction)]}\n\n${MAIL_SIGNATURE}`;
  }
}

export class MockMailer implements Mailer {
  readonly provider = 'mock';
  async send(): Promise<SendResult> {
    return { ok: true, messageId: `mock-${randomUUID()}` };
  }
}

export interface Gateways {
  notifier: Notifier;
  restaurants: RestaurantGateway;
  storeOrders: StoreOrderGateway;
  waitlists: WaitlistGateway;
  peerAgents: PeerAgentGateway;
  peerReplies: PeerReplyGateway;
  mailAssistant: MailAssistant;
  mailer: Mailer;
}

export const mockGateways = (now?: () => Date): Gateways => ({
  notifier: new MockNotifier(),
  restaurants: new MockRestaurantGateway(),
  storeOrders: new MockStoreOrderGateway(now),
  waitlists: new MockWaitlistGateway(),
  peerAgents: new MockPeerAgentGateway(),
  peerReplies: new MockPeerReplyGateway(),
  mailAssistant: new MockMailAssistant(),
  mailer: new MockMailer(),
});
