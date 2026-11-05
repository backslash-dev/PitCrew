# PitCrew

운전 중 말 한마디로 예약·주문·연락을 대신 처리하는 차량용 AI 실행 에이전트. 현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작.

| 경로 | 내용 |
| --- | --- |
| `app/` | Pleos Connect(AAOS, Android 14) 앱. Kotlin · Jetpack Compose |
| `server/` | 의도 파악·서비스 연동 서버. Node · TypeScript · Fastify |
| `scripts/` | 에뮬레이터 실행, Virtual Console(차량 상태 주입) 보조 스크립트 |

## 요구 사항

- JDK 17, Android SDK(platform 35), Pleos Connect Emulator 2.1.0 AVD
- Node 22.9 이상, pnpm 10

## 실행

```bash
scripts/emulator.sh start          # 헤드리스 부팅, 창이 필요하면 --window
cd app && ./gradlew installDebug   # 런처에서 PitCrew 실행
cd server && pnpm install && pnpm dev
```

서버는 기본 `127.0.0.1:3000`에서 뜨고, 에뮬레이터 안에서는 `http://10.0.2.2:3000`으로 접근한다.

차량 상태는 Virtual Console로 바꾼다.

```bash
scripts/vconsole.sh drive 40   # D단 40km/h
scripts/vconsole.sh park       # P단 정차
scripts/vconsole.sh state
```

## 테스트

```bash
cd app && ./gradlew testDebugUnitTest
cd server && pnpm typecheck && pnpm test
```

## 플랫폼 연동 현황

앱 첫 화면은 SDK 연결 진단 화면이다. 각 연동은 `app/src/main/java/ai/pitcrew/app/platform/`에 있다.

| 기능 | 연동 | 권한 |
| --- | --- | --- |
| 기어·주행 규제 | AAOS `CarPropertyManager`(GEAR_SELECTION) · `CarUxRestrictionsManager` | `CAR_POWERTRAIN` |
| 배터리 잔량 | Vehicle SDK 2.3.2 | `CAR_ENERGY` |
| 목적지·ETA·경유지 | NaviHelper 3.0.5 | `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH` |
| 현재 위치 | FusedLocation 1.1.3 | `FUSED_LOCATION`(런타임 요청) |
| 음성 인식 | SpeechToText 3.3.1 (ON_DEVICE) | `STT_SERVICE` |
| 음성 안내 | TextToSpeech 3.3.1 (ON_DEVICE) | `TTS_SERVICE` |

## 에뮬레이터 주의 사항

- 내비 경로·ETA가 고정 목업 값이라 주행해도 남은 시간이 줄지 않는다. 지각 시나리오 시연은 이 값을 기준으로 맞춘다.
- DO(Distraction Optimization) 미선언 앱도 주행 중에 막히지 않는다. 실차·심사에서는 막히므로 주행 중 상호작용은 음성으로 설계한다.
- 문자 발송(telephony)이 없다. 알림은 서버에서 보낸다.
- 앱은 AAOS user 10에서 돈다. 파일을 넣을 때는 `adb shell run-as ai.pitcrew.app --user 10 ...`.
- 음성 인식은 무음이면 스스로 끝나지 않는다. 종료 시점은 앱이 정해야 한다.
