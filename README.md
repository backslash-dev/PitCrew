# PitCrew

운전 중 말 한마디로 예약·주문·연락을 대신 처리하는 차량용 AI 실행 에이전트. 현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작.

| 경로 | 내용 |
| --- | --- |
| `app/` | Pleos Connect(AAOS, Android 14) 앱. Kotlin · Jetpack Compose |
| `server/` | 일행·예약·매장·주문·알림 API. Node · TypeScript · Fastify · MySQL |
| `scripts/` | 에뮬레이터 실행, Virtual Console(차량 상태 주입) 보조 스크립트 |

## 요구 사항

- JDK 17, Android SDK(platform 35), Pleos Connect Emulator 2.1.0 AVD
- Node 22.9 이상, pnpm 10

## 실행

```bash
scripts/emulator.sh start          # 헤드리스 부팅, 창이 필요하면 --window
cd app && ./gradlew installDebug   # 런처에서 PitCrew 실행
cd server && pnpm install && pnpm db:up && pnpm seed && pnpm dev
```

- 서버는 기본 `127.0.0.1:3000`에서 뜨고, debug 앱은 에뮬레이터 안에서 `http://10.0.2.2:3000`으로 붙는다. 서버 주소를 비우면(`-Ppitcrew.serverUrl=`) 앱 안 목업으로 동작한다.
- DB는 Docker의 MySQL 8.4(호스트 3308)다. 서버가 뜰 때 `migrations/`를 순서대로 적용하고, `pnpm seed`는 지금 시각 기준의 시연용 일행·예약을 넣는다.
- 운영 배포 시 `DATABASE_URL`, `API_TOKEN`(16자 이상, 필수)을 환경변수로 준다. 앱은 `-Ppitcrew.serverUrl=… -Ppitcrew.apiToken=…`로 빌드한다.

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

## Gleo 연동

`app/src/main/assets/schema.json`에 함수 2개(`NOTIFY_LATE_ARRIVAL`, `ORDER_PICKUP_ON_ROUTE`)를 정의했고, `GleoActionReceiver`가 방송(component `service`)으로 받는다. 확정이 필요한 작업은 `asking`으로 되물은 뒤, 사용자가 승인하면 Gleo가 `confirmedTime`/`confirmed`를 붙여 다시 호출한다.

마이크 없이 Gleo에 발화를 넣을 수 있다(에뮬레이터 Gleo의 테스트 입력).

```bash
adb shell "am broadcast -a ai.umos.intent.action.ASR --es ASR '피트크루 앱으로 11시 예약 지각 처리해줘'"
```

- Gleo는 action을 문서와 달리 `<패키지명>.<함수명>`으로 보낸다. 매니페스트는 두 형태를 모두 받는다.
- "일행에게 알려줘"처럼 문자·전화로 들리는 말은 Gleo 기본 메시지 기능으로 간다. 앱 이름("피트크루 앱으로 …")을 넣으면 안정적이다.
- 강제 종료(force-stop)되었거나 설치 후 한 번도 실행하지 않은 앱에는 방송이 오지 않는다. 설치 후 한 번은 앱을 열어야 한다.
- Gleo는 7초 안에 콜백을 기다린다. 앱은 5초 예산으로 응답한다.

## 앱 기능

- 지각 대응, 경로상 픽업: Gleo 음성 또는 주차 중 앱의 "말로 시키기"(STT)로 요청하고, 주행 중에는 음성, 주차 중에는 화면 탭으로 승인한다.
- 픽업 매장은 후보 중에서 바꿀 수 있고, 매장 3분·800m 이내에 다가가면 TTS로 안내한다.
- 예약 변경·알림 발송·주문·매장 조회는 서버가 처리한다. 식당·매장·문자 연동은 서버의 어댑터 인터페이스 뒤 목업이며, 발송·요청 이력은 DB에 남는다.
- 주행 중에는 조작 요소 없는 주행 화면으로 바뀐다(MainActivity에 DO 선언).

## 에뮬레이터 주의 사항

- 내비 경로·ETA가 고정 목업 값이라 주행해도 남은 시간이 줄지 않는다. 지각 시나리오 시연은 이 값을 기준으로 맞춘다.
- DO(Distraction Optimization) 미선언 앱은 주행 중 시스템이 가린다. DO 허용 여부는 Pleos 심사에서 정해진다.
- Gleo로 목적지를 정해도 내비가 검색 결과에서 멈출 때가 있다. 그때는 내비에서 안내 시작을 누른다.
- Gleo 대화 뒤 시스템 UI(시트 온도 빠른 제어)가 반복해서 죽는 경우가 있다. 에뮬레이터를 다시 켜면 돌아온다.
- 문자 발송(telephony)이 없다. 알림은 서버에서 보낸다.
- 앱은 AAOS user 10에서 돈다. 파일을 넣을 때는 `adb shell run-as ai.pitcrew.app --user 10 ...`.
- 음성 인식은 무음이면 스스로 끝나지 않는다. 종료 시점은 앱이 정해야 한다.
