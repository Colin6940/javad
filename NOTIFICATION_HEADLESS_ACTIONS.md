# 알림 액션을 앱 화면 없이 실행하도록 변경

## 변경 목표

녹음 알림의 재생, 재생 초기화, 저장 및 카카오톡 공유 액션이 `MainActivity`를 열지 않고 실행되도록 정리했습니다. 사용자가 카카오톡 공유를 선택하면 카카오톡의 전송 대상 선택/전송 UI는 표시됩니다. 이 UI는 카카오톡이 사용자 확인을 받기 위해 필요한 외부 앱 화면이며, SongRecorder 편집 화면은 실행하지 않습니다.

## 구현 내용

- 재생/일시정지/재생 초기화 버튼은 `RecordingService`에 명령을 전달합니다. 서비스는 `AppAudioRuntime`의 `AudioEngine`을 통해 `AudioTrack` 재생을 직접 시작·정지하고, 기존 알림의 재생 위치/상태를 업데이트합니다.
- 저장 버튼은 `RecordingService`에서 파일을 렌더링하고 `AudioEngine.exportToMusicFolder()`를 호출합니다. 출력 포맷과 저장 위치 설정을 읽고, 완료/실패 메시지는 같은 알림의 상태 줄에 표시합니다. 파일명 입력 대화상자는 띄우지 않으며 `Song_<timestamp>` 기본 파일명을 사용합니다.
- 카카오톡 공유용 파일은 백그라운드에서 현재 DSP 이펙트 상태의 복사본으로 렌더링합니다. 공유 파일이 준비되면 알림의 공유 버튼에 `FileProvider` URI를 읽기 권한과 함께 전달하는 `PendingIntent`를 연결합니다. 해당 Intent는 `MainActivity`가 아니라 `com.kakao.talk`를 대상으로 하며, 카카오톡을 사용할 수 없을 때는 시스템 공유 선택기를 엽니다.
- 이펙트 토글 후에는 공유 파일을 다시 생성해 공유 파일에 현재 토글 상태가 반영되도록 합니다.
- 공유 파일이 준비되는 동안에는 공유 버튼을 숨기고, 알림 상태에 준비 중/완료/실패를 표시합니다.

## 검증 범위 및 한계

- Android 리소스 XML 파싱, 관련 ID/액션 연결 및 ZIP 무결성을 정적으로 검사했습니다.
- Kotlin 컴파일러를 Android SDK 및 Gradle 의존성 없이 실행했으므로 Android API/라이브러리 참조가 해결되지 않아 실제 Kotlin 빌드 검증이 되지 않았습니다. 문법 진단상 `expecting` 또는 `unexpected tokens` 구문 오류는 나타나지 않았지만, 이를 APK 빌드 성공으로 볼 수는 없습니다.
- `./gradlew :app:compileDebugKotlin --offline`은 Gradle 9.6.0 배포본이 캐시에 없어 내려받기를 시도했고, `services.gradle.org` DNS 오류로 실패했습니다.
- Galaxy Tab S7+, Galaxy S22 및 에뮬레이터 실기기 동작 검증은 아직 하지 않았습니다.
