-- 실제 에이전트·문자 연동 전까지 시드가 채운다
CREATE TABLE peer_profiles (
  contact_id CHAR(36) NOT NULL,
  has_agent TINYINT(1) NOT NULL,
  latitude DECIMAL(9, 6) NOT NULL,
  longitude DECIMAL(9, 6) NOT NULL,
  free_from DATETIME NOT NULL,
  free_until DATETIME NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (contact_id),
  CONSTRAINT fk_peer_profile_contact FOREIGN KEY (contact_id) REFERENCES contacts (id) ON DELETE CASCADE
);

-- 중간 지점 후보의 목업 카탈로그. 실제 POI 검색이 붙으면 대체한다
CREATE TABLE meetup_places (
  id VARCHAR(64) NOT NULL,
  name VARCHAR(50) NOT NULL,
  category VARCHAR(20) NOT NULL,
  latitude DECIMAL(9, 6) NOT NULL,
  longitude DECIMAL(9, 6) NOT NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (id)
);

CREATE TABLE meetups (
  id CHAR(36) NOT NULL,
  contact_id CHAR(36) NOT NULL,
  place_id VARCHAR(64) NOT NULL,
  meet_at DATETIME(3) NOT NULL,
  mode VARCHAR(10) NOT NULL,
  status VARCHAR(20) NOT NULL,
  reservation_id CHAR(36) NULL,
  shared_at DATETIME(3) NULL,
  cancelled_at DATETIME(3) NULL,
  failure VARCHAR(255) NULL,
  provider VARCHAR(20) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_meetup_contact FOREIGN KEY (contact_id) REFERENCES contacts (id) ON DELETE CASCADE,
  CONSTRAINT fk_meetup_place FOREIGN KEY (place_id) REFERENCES meetup_places (id),
  -- 앱에서 예약을 직접 지워도 약속 기록은 남긴다
  CONSTRAINT fk_meetup_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (id) ON DELETE SET NULL
);

CREATE TABLE meetup_requests (
  id CHAR(36) NOT NULL,
  contact_id CHAR(36) NOT NULL,
  status VARCHAR(20) NOT NULL,
  provider VARCHAR(20) NOT NULL,
  failure VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  replied_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_meetup_request_contact FOREIGN KEY (contact_id) REFERENCES contacts (id) ON DELETE CASCADE
);

-- 받은 메일함 목업. 실제 메일 연동이 붙으면 동기화한 메일을 둔다
CREATE TABLE mails (
  id CHAR(36) NOT NULL,
  sender_name VARCHAR(50) NOT NULL,
  sender_email VARCHAR(254) NOT NULL,
  subject VARCHAR(200) NOT NULL,
  body TEXT NOT NULL,
  summary VARCHAR(200) NULL,
  received_at DATETIME(3) NOT NULL,
  read_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_mails_received_at (received_at)
);

CREATE TABLE mail_replies (
  id CHAR(36) NOT NULL,
  mail_id CHAR(36) NOT NULL,
  instruction VARCHAR(200) NULL,
  body TEXT NOT NULL,
  status VARCHAR(20) NOT NULL,
  provider VARCHAR(20) NOT NULL,
  failure VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  sent_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_mail_replies_mail (mail_id, status),
  CONSTRAINT fk_mail_reply_mail FOREIGN KEY (mail_id) REFERENCES mails (id) ON DELETE CASCADE
);

INSERT INTO meetup_places (id, name, category, latitude, longitude) VALUES
  ('mp-gangnam', '모퉁이 카페 강남', 'cafe', 37.498500, 127.028000),
  ('mp-yangjae', '소담 카페 양재', 'cafe', 37.484600, 127.034200),
  ('mp-suseo', '정원 카페 수서', 'cafe', 37.487300, 127.101700),
  ('mp-sadang', '마루 식당 사당', 'restaurant', 37.476500, 126.981600),
  ('mp-sinchon', '바람 카페 신촌', 'cafe', 37.555100, 126.936800),
  ('mp-seongsu', '결 베이커리 성수', 'cafe', 37.544600, 127.055900),
  ('mp-pangyo', '온기 식당 판교', 'restaurant', 37.394700, 127.111000),
  ('mp-jamsil', '호수 카페 잠실', 'cafe', 37.511000, 127.098000),
  ('mp-yeouido', '하루 식당 여의도', 'restaurant', 37.521600, 126.924200);
