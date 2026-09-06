# ForShot

DJI 드론(Mini 4 Pro) 또는 폰 카메라로 피사체를 촬영하면, AI 서버(YOLO 객체 탐지 + 구도 분석)와 통신하며 드론을 자동으로 움직여 **삼분할 구도(Rule of Thirds)**에 맞는 사진을 찍어주는 Android 앱입니다.

## 핵심 기능

- **드론 / 폰 카메라 모드**: DJI 드론의 FPV 스트림을 사용하는 드론 모드와, 드론 없이 테스트할 수 있는 폰 카메라 모드를 지원합니다.
- **객체 탐지 및 선택**: 서버의 YOLO 탐지 결과를 화면에 오버레이로 표시하고, 탭으로 촬영 대상 객체를 선택합니다.
- **자동 촬영 파이프라인**: 촬영 버튼 하나로 `후진 → 구도 스캔 → 정점 복귀 → 세부조정 → 짐벌 틸트 → 최종 촬영`까지 전 과정을 자동 수행합니다.
- **비상정지**: 드론 모드에서 언제든 즉시 스틱을 0으로 만들고 진행 중인 동작을 취소할 수 있습니다.

## 기술 스택

- **플랫폼**: Android (Kotlin), minSdk 23 / target·compileSdk 37
- **드론 제어**: DJI Mobile SDK V5 (`dji-sdk-v5-aircraft`) — Virtual Stick으로 기체 이동 및 짐벌 제어
- **폰 카메라**: CameraX
- **네트워킹**: OkHttp3 + Kotlin Coroutines
- **AI 서버**: 이 레포에는 포함되어 있지 않으며, `AI_SERVER_URL`로 접속하는 별도 서버가 YOLO 탐지와 구도/정점 분석을 수행합니다. 앱은 순수 클라이언트입니다.

## 프로젝트 구조

```
Thirds/
├── app/src/main/java/com/photo/thirds/
│   ├── ThirdsApplication.kt     # Application 클래스 (DJI SDK 초기화, 크래시 로거)
│   ├── HomeActivity.kt          # 첫 화면: 드론/폰 모드 선택
│   ├── CameraActivity.kt        # 메인 화면: 프리뷰 + 탐지 + 촬영 + 자동 파이프라인
│   ├── FrameSource.kt           # 프레임 소스 공통 인터페이스
│   ├── DroneFrameSource.kt      # DJI SDK 연동 (연결/스트림/텔레메트리/실내모드)
│   ├── PhoneFrameSource.kt      # CameraX 연동 (폰 카메라 프레임/GPS)
│   ├── DroneMover.kt            # Virtual Stick 기반 드론 이동 로직
│   ├── GimbalTester.kt          # 짐벌 pitch 제어/테스트 유틸
│   ├── OverlayView.kt           # 탐지 박스 + 선택 표시 커스텀 View
│   └── PipelineProgressView.kt  # 자동 파이프라인 진행 표시 커스텀 View
└── flight_logs/                 # 실비행 로그 캡처본 (분석용)
```

## 빌드 및 실행

프로젝트 루트에 `local.properties` 파일을 만들고 아래 값을 설정합니다 (git에 커밋되지 않는 로컬 시크릿).

```properties
DJI_API_KEY=your_dji_developer_api_key
AI_SERVER_URL=http://<ai-server-host>:8000
```

- `DJI_API_KEY`: DJI 개발자 콘솔에서 발급받은 API 키 (`AndroidManifest.xml`의 `com.dji.sdk.API_KEY` 메타데이터로 주입됩니다)
- `AI_SERVER_URL`: YOLO 탐지 및 구도 분석을 수행하는 AI 서버 주소 (미설정 시 기본값 `http://192.168.0.11:8000` 사용)

이후 Android Studio에서 프로젝트를 열고 실제 기기(또는 DJI 드론)에 빌드/실행합니다. 드론 없이 테스트하려면 홈 화면에서 "폰 카메라 (테스트)" 모드를 선택하면 됩니다.

## 요구사항

- Android Studio (AGP 9.2.1, Kotlin 2.1.0)
- 실기기 (에뮬레이터 불가 — 카메라/USB 액세서리 필요)
- 드론 모드 테스트 시 DJI Mini 4 Pro 등 DJI Mobile SDK V5 호환 기체
