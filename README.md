# PitCrew

- 앱 이름: PitCrew
- 한 줄 소개: 내비 도착 시간·위치·주행 상태를 보고 지각 대응, 경로상 픽업, 충전 중 줄서기, 약속 조율, 메일 답장을 대신 처리하는 차 안의 비서

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

- `apk/백슬래시파트너스_PitCrew_release.apk`: 서명한 release 빌드(APK Signature Scheme v3)
