# 미리 듣기 알림 컨트롤 및 에뮬레이터 로그 점검

## 변경 사항

- 녹음 상태와 미리 듣기 상태 모두 `RecordingService.NOTIFICATION_ID`(ID 1)를 사용하므로, 녹음 완료 후 두 번째 알림을 만드는 대신 같은 알림 항목을 갱신합니다.
- `ControlState.REVIEW`에서도 접힌 화면(`notification_controls_compact.xml`)과 확장 화면(`notification_controls_expanded.xml`)을 지정했습니다.
- 미리 듣기 알림의 확장 화면에는 재생/일시정지, 재생 초기화, 노이즈 게이트, EQ, 컴프레서, 리버브, 리버브 EQ, 저장 및 카톡 공유가 표시됩니다.
- `Notification.DecoratedMediaCustomViewStyle`을 사용해 시스템 알림의 헤더/장식 및 MediaSession 연결을 유지하면서 사용자 정의 확장 레이아웃을 표시합니다.
- 이전 `RemoteViews` 문제를 재발시키지 않도록 `setClickable`/`setEnabled` 같은 동적 메서드 호출은 사용하지 않습니다. 버튼은 `setOnClickPendingIntent`로 처리합니다.

## 제공된 에뮬레이터 로그에 대한 해석

- `RemoteViews$ActionException: ... setClickable(boolean)` 및 `BadForegroundServiceNotificationException`: 앞선 실행의 치명적 오류입니다. 알림 사용자 정의 레이아웃을 시스템이 부풀리는(inflate) 중 허용되지 않은 `setClickable` 호출이 실행되어 포그라운드 서비스 알림 게시가 실패하고 앱 프로세스가 종료됐습니다. 로그 파일 안에는 구버전 실행의 오류가 반복 포함돼 있습니다.
- `AppHibernationService: Package ... is not installed for any user`: 해당 시각에 시스템이 앱 설치 상태를 조회했을 때 패키지가 없었던 로그입니다. 이후 프로세스가 정상 시작된 로그도 있어, 이 메시지만으로 앱 코드의 시작 실패라고 판단할 수 없습니다.
- `Finsky ... ItemStore: getItems RPC failed`: 에뮬레이터의 Google Play Store가 패키지 정보를 질의하다 실패한 로그입니다. 녹음 기능의 직접 원인이라는 증거는 없습니다.
- `Access denied finding property vendor.mesa.virtgpu.kumquat`, `Failed to choose config with EGL_SWAP_BEHAVIOR_PRESERVED`, `Failed to initialize 101010-2 format`, `Unknown dataspace 0`: 에뮬레이터의 Mesa/가상 GPU 및 그래픽 포맷 호환성 경고입니다. 로그상으로는 렌더링 설정을 대체 시도하고 있으며, 그 자체로 앱 크래시를 뜻하지 않습니다.
- `Choreographer: Skipped ... frames` 및 `Davey! duration=...`: 시작 화면을 그리는 동안 메인 스레드가 프레임 마감 시각을 놓쳤다는 성능 경고입니다. 일부는 Compose/JIT의 첫 실행 비용일 수 있습니다. 매번 반복되어 UI가 버벅이면 별도로 시작 작업을 프로파일링해야 합니다.
- `hiddenapi ... SystemProperties`, `startup_agents`, `Redefining intrinsic method`: Android Studio 실행/프로파일링 계측과 Android 내부 API 접근에 관한 경고입니다. 제공된 로그에서는 치명적 종료와 연결되어 있지 않습니다.
- `AppOps: attributionTag not declared in manifest`: 오디오 사용 중 Android AppOps 계층에서 남긴 경고입니다. 앱 코드에서 명시적인 attribution tag 사용은 발견되지 않았고, 제공된 최신 구간에서는 이 로그 뒤에도 `AudioTrack.stop`과 앱 실행이 이어집니다. 현재 근거로는 이것을 크래시 원인으로 볼 수 없습니다. 임의의 manifest 태그를 추가하지 않았습니다.

## 검증 범위

- 알림 레이아웃 XML 파싱 및 Kotlin의 변경 부분 확인을 수행했습니다.
- `RemoteViews`의 `setClickable`/`setEnabled` 동적 메서드 호출이 남아 있지 않은 것을 확인했습니다.
- 실제 APK 빌드는 Gradle 9.6.0 다운로드가 `services.gradle.org` DNS 오류로 실패하여 완료하지 못했습니다. Galaxy Tab S7+, Galaxy S22 또는 에뮬레이터에서 새 ZIP을 실제 실행 검증하지는 못했습니다.
