-- 같은 요청을 다시 보내도(응답 유실 뒤 재시도) 주문·문자·예약 변경이 두 번 일어나지 않게 결과를 저장한다
CREATE TABLE idempotency_keys (
  scope VARCHAR(40) NOT NULL,
  idem_key VARCHAR(100) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status_code SMALLINT UNSIGNED NULL,
  response_body MEDIUMTEXT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (scope, idem_key),
  KEY idx_idempotency_created_at (created_at)
);

-- 외부 시스템 결과를 기다리는 동안의 상태(PENDING)와 결과를 모를 때(UNKNOWN)의 사유를 남긴다
ALTER TABLE orders ADD COLUMN failure VARCHAR(255) NULL;
ALTER TABLE waitlists ADD COLUMN failure VARCHAR(255) NULL;

-- 같은 요청 안에서 이미 보낸 사람에게는 다시 보내지 않도록 요청 키와 받는 사람을 묶는다
ALTER TABLE notifications
  ADD COLUMN contact_id CHAR(36) NULL,
  ADD COLUMN request_key VARCHAR(100) NULL,
  ADD UNIQUE KEY uq_notification_request (request_key, contact_id);
