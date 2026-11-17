# rfscan — 안드로이드 블루투스·Wi-Fi 수동 탐지 SDK

시험실 같은 공간에서 **주변 블루투스(BLE)와 Wi-Fi 신호원**을 감지하는 안드로이드 라이브러리입니다. 신호원의 개수, 종류(이어폰·스마트안경·폰 핫스팟·무선 카메라 등), 세기를 알려줍니다.

- **수신 전용:** 기기에 연결하거나 신호를 보내지 않습니다.
- **식별자 비저장:** MAC/BSSID는 스캔마다 바뀌는 해시로만 씁니다.
- **이름 비공개:** 기기 이름과 SSID는 기본적으로 결과에서 빠집니다(`includeNames = false`).

```
rfscan/   ← SDK (Android Library, minSdk 26)
app/      ← SDK를 쓰는 샘플 앱 "전파 탐지" (탭: 순찰 모드 / 10초 스캔)
```

## 사용법

```kotlin
val scanner = RfScanner(context)

// 1) 권한
if (scanner.missingPermissions().isNotEmpty())
    permissionLauncher.launch(RfScanner.requiredPermissions())

// 2) 준비 상태 (블루투스·Wi-Fi·위치 켜짐 여부)
scanner.readiness().forEach { showWarning(it) }

// 3) 스캔 (코루틴, 기본 10초)
val report = scanner.scan(RfScanConfig(durationMs = 10_000, bleRssiMin = -75, wifiRssiMin = -80)) { partial ->
    render(partial)                 // 0.5초마다 중간 결과
}
report.strong      // 기준 세기 이상 신호원 (위험도·세기 순)
report.highRisk    // 위험도 3: 이어폰, 스마트안경, 자작 보드, 폰 핫스팟, 무선 카메라, Wi-Fi Direct
report.kindCounts() // {"휴대폰 핫스팟 추정": 1, "AirPods·Beats 이어폰": 1}
report.warnings    // 스캔이 불완전했던 이유 (Wi-Fi 횟수 제한 등)
```

권한(블루투스 스캔, Wi-Fi 상태, 주변 Wi-Fi 기기, 위치)은 SDK 매니페스트에서 앱으로 자동으로 합쳐집니다. SDK는 네트워크를 쓰지 않으며, 어떤 결과도 밖으로 보내지 않습니다.

### 순찰 모드 (`RfPatrol`)

복도를 걸으면서 계속 감지하고, 교실 문 앞마다 기록해 **어느 교실에서 신호가 가장 센지** 비교합니다.

```kotlin
val patrol = RfPatrol(context)
patrol.start(lifecycleScope, PatrolConfig(rssiMin = -88))   // 복도는 벽 감쇠로 -85~-90 권장
lifecycleScope.launch { patrol.state.collect { render(it) } }

patrol.mark("301호")   // 301호 문 앞에서
patrol.mark("302호")   // 302호 문 앞에서 …

patrol.state.value.strongest   // 미터용: 지금 가장 센 신호 (세기는 이동평균)
patrol.state.value.verdicts()  // 위치별 비교: 각 신호를 가장 세게 잡힌 위치에 배정, 가장 의심되는 위치가 맨 앞
patrol.stop()
```

- **신호 수집:** 블루투스는 계속 수신합니다. Wi-Fi는 `wifiIntervalMs`(기본 5초) 간격으로 재측정합니다. Wi-Fi 검색 제한을 꺼야 이 간격대로 측정됩니다.
- **세기 계산:** 걸을 때 흔들림을 줄이려고 이동평균을 씁니다. 6초(블루투스) / 20초(Wi-Fi) 동안 다시 안 보인 신호는 목록에서 뺍니다.
- **위치 비교:** 같은 신호원은 순찰 세션 동안 같은 id를 가집니다. 그래서 여러 문 앞에서 잡힌 신호를 비교할 수 있습니다.
- **화면 유지:** 화면이 꺼지면 안드로이드가 BLE 스캔을 멈출 수 있으니 순찰 중에는 화면을 켜 두세요. 샘플 앱은 자동으로 켜 둡니다.

## 판별 기준

- **블루투스**
  - 광고 이름
  - 제조사 ID(Apple, Samsung, Meta, Espressif, Nordic 등)
  - Apple 신호 유형: AirPods, AirTag, iPhone
- **Wi-Fi**
  - SSID 패턴: 폰 핫스팟, 무선 카메라, `DIRECT-`
  - 숨김 SSID
  - 임의(로컬 관리) MAC

모두 **추정**입니다. 학교 Wi-Fi를 전부 끈 상태라면, 교실 안에서 강하게 잡히는 Wi-Fi는 종류와 상관없이 의심 대상입니다.

## 배포

### A. 샘플 앱 APK (감독관 폰에 설치)
```bash
./gradlew :app:assembleRelease   # 또는 Android Studio: Build > Build APK(s)
```
여러 사람에게 나눠 주고 업데이트할 계획이면 **서명된 릴리스 APK**를 쓰세요(Build > Generate Signed App Bundle / APK). 키스토어 파일은 반드시 백업해 두세요. 키를 잃어버리면 이후 업데이트를 기존 앱 위에 설치할 수 없습니다.

### B. SDK를 다른 앱에서 쓰게 배포

1. **Gradle 래퍼 생성 (최초 1회, Mac):**
   ```bash
   brew install gradle
   gradle wrapper --gradle-version 8.9
   ```
   생성된 `gradlew`, `gradle/wrapper/gradle-wrapper.jar`도 함께 커밋합니다.
2. **JitPack (가장 간단):** GitHub에 올리고 `v0.1.0` 태그를 만들면 JitPack이 자동으로 빌드합니다. 쓰는 쪽에서는 이렇게 추가합니다.
   ```kotlin
   // settings.gradle.kts
   maven("https://jitpack.io")
   // app/build.gradle.kts
   implementation("com.github.<GitHub ID>:<저장소 이름>:v0.1.0")
   ```
3. **GitHub Packages (비공개 저장소):**
   ```bash
   GPR_REPO=<owner>/<repo> GPR_USER=<id> GPR_TOKEN=<토큰> ./gradlew :rfscan:publish
   ```
4. **AAR 파일:** `./gradlew :rfscan:assembleRelease` → `rfscan/build/outputs/aar/rfscan-release.aar`
   - AAR만 받아 쓰는 앱은 `kotlinx-coroutines-android` 의존성을 직접 추가해야 합니다.

## 현장 설정 팁

- **Wi-Fi 스캔 횟수 제한:** 안드로이드는 Wi-Fi 스캔을 **2분에 4회**로 제한합니다. 감독관 폰에서 **개발자 옵션 → "Wi-Fi 검색 제한"**을 끄면 버튼을 누를 때마다 새로 측정합니다.
  - 끄지 않으면 제한에 걸렸을 때 최근 결과(최대 2분 전)를 쓰고, 화면에 그렇게 표시합니다.
- **스캔에 필요한 설정:** 위치(GPS)를 켜야 하고, Wi-Fi도 켜 둬야 합니다(어디에 연결할 필요는 없음).

## 검증 상태

- **SDK(`rfscan`):** Android API 34 기준으로 Kotlin 2.0 컴파일러 타입 검사를 통과했습니다. 다만 코루틴과 androidx는 실제 라이브러리 대신 시그니처만 맞춘 대체 코드로 검사했습니다.
- **로직 단위 테스트:** 판별 로직과 위치 비교 로직은 실행 테스트를 통과했습니다.
- **샘플 앱(Compose UI):** 컴파일하지 않았습니다.
- **실기기 테스트:** 하지 않았습니다. 배포 전에 반드시 여러 기종의 실제 폰에서 시험하세요.
