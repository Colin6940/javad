# 이펙트 도움말 변경 내역

## UI 변경
- 노이즈 게이트, EQ, 컴프레서, 리버브 ON/OFF 옆에 물음표 모양 도움말 버튼을 추가했습니다.
- 버튼에는 접근성 이름 `도움말`을 지정했으며, 누르면 해당 기능 설명이 AlertDialog로 표시됩니다.
- 리버브 안의 전용 EQ ON/OFF에도 별도 도움말을 추가했습니다.
- EQ의 전체 ON/OFF는 화면 상단에서 `dsp.eqEnabled`를 제어하도록 했습니다.
- EQ 하단의 선택 밴드 ON/OFF 버튼은 제거했습니다. 밴드 버튼을 다시 눌러 개별 밴드를 켜고 끄는 기존 동작은 유지했습니다.

## 도움말 설명의 정확성
- 노이즈 게이트는 신호를 완전히 무시한다고 단정하지 않고, 임계값보다 낮은 대역의 음량을 크게 낮춘다고 설명합니다.
- 컴프레서 설명은 컴프레서가 작은 소리만 직접 키운다고 표현하지 않고, Threshold·Ratio·Makeup Gain의 역할을 나눠 설명합니다.
- 리버브 설명은 원래 소리에 추가되는 잔향 신호의 양, Low Cut, Reverb Time, Pre-delay, Pre/Post 신호 경로를 설명합니다.

## 검증
- 수정한 Kotlin 파일들을 Kotlin 컴파일러에 전달해 구문 오류 메시지가 없는지 확인했습니다. Compose/Android 라이브러리를 클래스패스에 포함하지 못했으므로 이 검사는 Android 컴파일 성공을 뜻하지 않습니다.
- `./gradlew :app:compileDebugKotlin` 실행은 Gradle 9.6.0 배포본을 받기 위한 `services.gradle.org` 연결이 불가능해 실패했습니다. Android Studio에서 프로젝트를 열고 Gradle 동기화 및 앱 빌드를 확인해야 합니다.
