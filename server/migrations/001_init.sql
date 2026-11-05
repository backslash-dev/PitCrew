CREATE TABLE contacts (
  id CHAR(36) NOT NULL,
  name VARCHAR(50) NOT NULL,
  phone VARCHAR(20) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id)
);

CREATE TABLE reservations (
  id CHAR(36) NOT NULL,
  place_name VARCHAR(100) NOT NULL,
  reserved_at DATETIME NOT NULL,
  party_size SMALLINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_reservations_reserved_at (reserved_at)
);

CREATE TABLE reservation_companions (
  reservation_id CHAR(36) NOT NULL,
  contact_id CHAR(36) NOT NULL,
  PRIMARY KEY (reservation_id, contact_id),
  CONSTRAINT fk_companion_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (id) ON DELETE CASCADE,
  CONSTRAINT fk_companion_contact FOREIGN KEY (contact_id) REFERENCES contacts (id) ON DELETE CASCADE
);

CREATE TABLE reservation_changes (
  id CHAR(36) NOT NULL,
  reservation_id CHAR(36) NOT NULL,
  previous_at DATETIME NOT NULL,
  requested_at DATETIME NOT NULL,
  status VARCHAR(20) NOT NULL,
  provider VARCHAR(20) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_change_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (id) ON DELETE CASCADE
);

-- 경로상 매장 검색의 목업 카탈로그. 실제 POI 검색이 붙으면 대체한다
CREATE TABLE stores (
  id VARCHAR(64) NOT NULL,
  name VARCHAR(50) NOT NULL,
  branch VARCHAR(50) NOT NULL,
  latitude DECIMAL(9, 6) NOT NULL,
  longitude DECIMAL(9, 6) NOT NULL,
  detour_minutes SMALLINT UNSIGNED NOT NULL,
  prep_minutes SMALLINT UNSIGNED NOT NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (id)
);

CREATE TABLE orders (
  id CHAR(36) NOT NULL,
  store_id VARCHAR(64) NOT NULL,
  item VARCHAR(100) NOT NULL,
  pickup_at DATETIME NOT NULL,
  status VARCHAR(20) NOT NULL,
  provider VARCHAR(20) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_orders_created_at (created_at),
  CONSTRAINT fk_order_store FOREIGN KEY (store_id) REFERENCES stores (id)
);

CREATE TABLE notifications (
  id CHAR(36) NOT NULL,
  recipient_name VARCHAR(50) NOT NULL,
  recipient_phone VARCHAR(20) NOT NULL,
  message VARCHAR(1000) NOT NULL,
  status VARCHAR(20) NOT NULL,
  provider VARCHAR(20) NOT NULL,
  provider_message_id VARCHAR(100) NULL,
  error VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_notifications_created_at (created_at)
);

INSERT INTO stores (id, name, branch, latitude, longitude, detour_minutes, prep_minutes) VALUES
  ('s-semo-yeoksam', '세모 커피', '역삼점', 37.500600, 127.036400, 2, 6),
  ('s-noeul-seocho', '노을 베이커리', '서초점', 37.492500, 127.015600, 4, 10),
  ('s-hansot-gangnam', '한솥밥상', '강남점', 37.497900, 127.027600, 3, 12);
