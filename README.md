# SongRecorder (Java)

음성녹음기 with 이펙트 체인 — Kotlin/Jetpack Compose 원본을 **Java + Android View(Material 3)** 로 옮긴 버전입니다.

- 오디오 엔진·DSP(게이트/EQ/컴프레서/플레이트 리버브)·알림 서비스 로직은 원본과 동일하게 Java로 이식
- Compose 화면은 프로그래매틱 View로 재작성 (`RecorderEditorView`, `FileBrowserView`, 각 이펙트 에디터)
- Kotlin 코루틴은 `ExecutorService` + 메인 `Handler`로 대체

## 빌드

GitHub Actions(`.github/workflows/android.yml`)가 push마다 디버그 APK를 빌드하고 단위 테스트를 실행합니다.
로컬에서는 Android Studio로 열거나 `./gradlew assembleDebug`.
