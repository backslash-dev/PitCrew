ALTER TABLE reservation_changes ADD COLUMN reverted_at DATETIME(3) NULL;

ALTER TABLE orders
  ADD COLUMN cancelled_at DATETIME(3) NULL,
  ADD KEY idx_orders_pickup_at (pickup_at);

-- 경로상 충전소 검색의 목업 카탈로그. 실제 충전소 API(환경부 등)가 붙으면 대체한다
CREATE TABLE chargers (
  id VARCHAR(64) NOT NULL,
  name VARCHAR(50) NOT NULL,
  latitude DECIMAL(9, 6) NOT NULL,
  longitude DECIMAL(9, 6) NOT NULL,
  detour_minutes SMALLINT UNSIGNED NOT NULL,
  power_kw SMALLINT UNSIGNED NOT NULL,
  available_stalls SMALLINT UNSIGNED NOT NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (id)
);

CREATE TABLE charger_restaurants (
  id VARCHAR(64) NOT NULL,
  charger_id VARCHAR(64) NOT NULL,
  name VARCHAR(50) NOT NULL,
  walk_minutes SMALLINT UNSIGNED NOT NULL,
  wait_minutes SMALLINT UNSIGNED NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_restaurant_charger FOREIGN KEY (charger_id) REFERENCES chargers (id)
);

CREATE TABLE waitlists (
  id CHAR(36) NOT NULL,
  restaurant_id VARCHAR(64) NOT NULL,
  party_size SMALLINT UNSIGNED NOT NULL,
  queue_position SMALLINT UNSIGNED NOT NULL,
  wait_minutes SMALLINT UNSIGNED NOT NULL,
  status VARCHAR(20) NOT NULL,
  provider VARCHAR(20) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  cancelled_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_waitlist_restaurant FOREIGN KEY (restaurant_id) REFERENCES charger_restaurants (id)
);

INSERT INTO chargers (id, name, latitude, longitude, detour_minutes, power_kw, available_stalls) VALUES
  ('ch-ieum-yangjae', '이음차지 양재', 37.484600, 127.034200, 3, 350, 2),
  ('ch-green-seocho', '그린차지 서초', 37.491800, 127.009600, 6, 100, 4);

INSERT INTO charger_restaurants (id, charger_id, name, walk_minutes, wait_minutes) VALUES
  ('cr-sobok-yangjae', 'ch-ieum-yangjae', '소복 국밥', 2, 15),
  ('cr-mulgyeol-yangjae', 'ch-ieum-yangjae', '물결 우동', 4, 25),
  ('cr-dalbit-seocho', 'ch-green-seocho', '달빛 분식', 3, 10);
