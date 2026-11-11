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

ALTER TABLE orders ADD COLUMN failure VARCHAR(255) NULL;
ALTER TABLE waitlists ADD COLUMN failure VARCHAR(255) NULL;

-- 같은 요청으로 이미 보낸 사람에게 다시 보내지 않게 한다
ALTER TABLE notifications
  ADD COLUMN contact_id CHAR(36) NULL,
  ADD COLUMN request_key VARCHAR(100) NULL,
  ADD UNIQUE KEY uq_notification_request (request_key, contact_id);
