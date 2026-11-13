<div align="center">

# PitCrew

### 운전에 집중하는 동안, 나머지는 PitCrew가.

내비 도착 시간·위치·주행 상태를 읽고 일정을 챙기는 **차 안의 비서**

[**APK 다운로드**](apk/백슬래시파트너스_PitCrew_release.apk?raw=true) · [**데모 영상 보기 · 3분 53초**](docs/demo/pitcrew-demo.mp4) · [**바로 실행하기**](#실행-방법)

</div>

---

## 데모

늦을 것 같으면 먼저 묻고, 가는 길에 픽업하고, 주차 후 메일까지 마무리합니다.
**Pleos Connect 에뮬레이터에서 촬영한 실제 시연 화면**입니다.

[![PitCrew 지각 대응 데모 — 도착 예정 시간을 보고 예약 변경과 일행 알림을 제안하는 화면](docs/demo/late-arrival.jpg)](docs/demo/pitcrew-demo.mp4)

> **도착 시간에서 다음 행동까지.** 예약보다 늦게 도착할 상황을 감지하면 예약 시간 변경과 일행 알림을 제안합니다.
> 위 이미지를 누르면 전체 데모 영상으로 이동합니다.

### 가는 길에, 말 한마디로 픽업

“피트크루 앱으로 가는 길에 노을 베이커리에서 크루아상 픽업 주문해줘.”

경로와 픽업 시간을 함께 확인하고, 주문 후 내비게이션에 경유지를 추가합니다.

![경로상 픽업 데모 — 크루아상 픽업 시간과 예상 우회 시간을 안내하는 화면](docs/demo/pickup.jpg)

<details>
<summary><strong>충전 중 줄서기 · 중간 지점 약속 조율도 보기</strong></summary>

### 충전하는 동안 줄서기

배터리 잔량을 보고 충전 중 이용할 수 있는 식당 줄서기를 제안합니다.

![충전 중 줄서기 데모 — 배터리 18%에서 충전하는 동안 식당 줄서기를 제안하는 화면](docs/demo/charging.jpg)

### 서로의 중간에서 만나기

상대의 위치와 빈 시간을 고려해 만날 장소를 조율하고, 예약 내용을 상대 에이전트에도 전달합니다.

![약속 조율 데모 — 중간 지점 카페 예약과 상대 에이전트 전달 결과 화면](docs/demo/meetup.jpg)

</details>

### 메일은 주차 후 확인하고 전송

운전 중에는 메일 내용을 음성으로 확인하고 답장을 준비합니다. 차를 세운 뒤 화면에서 확인해 전송합니다.

![메일 답장 데모 — 주차 후 확인을 거쳐 답장 전송을 완료한 화면](docs/demo/mail.jpg)

---

## 실행 방법

### APK로 바로 실행

Pleos Connect 에뮬레이터를 켠 상태에서 설치한다.

```bash
adb install apk/백슬래시파트너스_PitCrew_release.apk
```

런처에서 PitCrew를 연다. release 빌드는 서버 없이 앱 안 목업 데이터(일행·예약·매장·메일)로 동작한다.

### 소스에서 실행(서버 연동)

```bash
scripts/emulator.sh start          # 헤드리스 부팅, 창이 필요하면 --window
cd app && ./gradlew installDebug   # 런처에서 PitCrew 실행
cd server && pnpm install && pnpm db:up && pnpm seed && pnpm dev
```

- 서버는 기본 `127.0.0.1:3000`에서 뜨고, debug 앱은 에뮬레이터 안에서 `http://10.0.2.2:3000`으로 붙는다.
- DB는 Docker의 MySQL 8.4(호스트 3308)다. `pnpm seed`는 지금 시각 기준의 시연용 데이터를 넣는다.

## 테스트 환경 및 필요한 설정

- 개발 환경: JDK 17, Android SDK(platform 35), Node 22.9 이상, pnpm 10, Docker(MySQL 8.4)
- 설치 후 앱을 한 번 열어야 Gleo 음성 명령이 앱에 전달된다(강제 종료한 뒤에도 마찬가지).
- 처음 실행할 때 사용 안내(온보딩)에서 "위치 사용 허용"을 눌러 위치 권한을 준다.
- 지각 대응·경로상 픽업·충전은 내비게이션에 목적지를 정해 경로가 있어야 동작한다.
- 에뮬레이터 시간대는 서울로 둔다(`settings put global auto_time_zone 0`, `cmd alarm set-timezone Asia/Seoul`).
- 차량 상태(기어·속도·배터리)는 Virtual Console로 바꾼다.

  ```bash
  scripts/vconsole.sh drive 40   # D단 40km/h
  scripts/vconsole.sh park       # P단 정차
  scripts/vconsole.sh battery 18 # 배터리 18%
  ```

- 마이크 없이 Gleo에 발화를 넣을 수 있다(에뮬레이터 Gleo의 테스트 입력).

  ```bash
  adb shell "am broadcast -a ai.umos.intent.action.ASR --es ASR '피트크루 앱으로 11시 예약 지각 처리해줘'"
  ```

## 에뮬레이터 버전과 화면 규격

- Pleos Connect Emulator 2.1.0 (Android 14, API 34, `pleos_car_emulator` arm64-v8a 시스템 이미지)
- 화면 2560×1440, 160dpi

## APK 파일

- [백슬래시파트너스_PitCrew_release.apk 다운로드](apk/백슬래시파트너스_PitCrew_release.apk?raw=true): 서명한 release 빌드(APK Signature Scheme v3)
