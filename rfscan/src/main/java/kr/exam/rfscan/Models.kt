package kr.exam.rfscan

/** 신호 종류 */
enum class SignalType { BLE, WIFI }

/**
 * 스캔 설정.
 *
 * @param durationMs   한 번 스캔하는 시간 (BLE 수신 시간이자 Wi-Fi 결과 대기 한도)
 * @param bleRssiMin   이보다 센(가까운) BLE 기기만 [RfScanReport.strong] 에 포함
 * @param wifiRssiMin  이보다 센 Wi-Fi 신호만 [RfScanReport.strong] 에 포함
 * @param includeNames true 면 기기 이름·SSID 를 결과에 포함. 기본값 false — 개인 이름이 든
 *                     SSID("홍길동의 iPhone")가 화면·서버로 나가지 않도록 종류만 제공
 * @param wifiMaxAgeSec Wi-Fi 스캔이 횟수 제한에 걸려 캐시 결과를 쓸 때, 이보다 오래된 결과는 버림
 */
data class RfScanConfig(
    val durationMs: Long = 10_000,
    val bleRssiMin: Int = -75,
    val wifiRssiMin: Int = -80,
    val scanBle: Boolean = true,
    val scanWifi: Boolean = true,
    val includeNames: Boolean = false,
    val wifiMaxAgeSec: Int = 120,
)

/**
 * 탐지된 신호원 1개. 스캔 1회 동안만 메모리에 존재합니다.
 *
 * @param id     세션마다 바뀌는 salt 로 MAC/BSSID 를 해시한 값 — 같은 스캔 안에서 중복 제거용, 추적 불가
 * @param risk   3 = 고위험(이어폰·스마트안경·자작 보드·폰 핫스팟·무선 카메라), 2 = 확인 필요, 1 = 낮음
 * @param maker  BLE 제조사(있으면)
 * @param band   Wi-Fi 대역 "2.4GHz" / "5GHz" / "6GHz" (BLE 는 null)
 * @param name   [RfScanConfig.includeNames] 가 true 일 때만 채워짐
 */
data class RfDevice(
    val id: String,
    val type: SignalType,
    val kind: String,
    val risk: Int,
    val rssi: Int,
    val maker: String? = null,
    val band: String? = null,
    val name: String? = null,
    val packets: Int = 1,
)

/** 스캔 결과 */
data class RfScanReport(
    val startedAt: Long,
    val elapsedMs: Long,
    val finished: Boolean,
    val devices: List<RfDevice>,
    val config: RfScanConfig,
    /** Wi-Fi 결과가 이번 스캔에서 새로 측정된 것인지 (false 면 최근 캐시 결과) */
    val wifiFresh: Boolean,
    /** 스캔이 불완전했던 이유 등 사용자에게 보여줄 안내 */
    val warnings: List<String>,
) {
    /** 기준 세기 이상(가까운) 신호원만, 위험도·세기 순 */
    val strong: List<RfDevice>
        get() = devices.filter {
            it.rssi >= if (it.type == SignalType.BLE) config.bleRssiMin else config.wifiRssiMin
        }.sortedWith(compareByDescending<RfDevice> { it.risk }.thenByDescending { it.rssi })

    val highRisk: List<RfDevice> get() = strong.filter { it.risk >= 3 }

    /** 종류별 개수 (서버 보고용 — 식별자 없음) */
    fun kindCounts(onlyStrong: Boolean = true): Map<String, Int> =
        (if (onlyStrong) strong else devices).groupingBy { it.kind }.eachCount()
}
