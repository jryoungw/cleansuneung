package kr.exam.rfscan

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.exam.rfscan.internal.BleSource
import kr.exam.rfscan.internal.TrackStore
import kr.exam.rfscan.internal.wifiScanOnce
import java.security.SecureRandom

/**
 * 순찰 모드 설정.
 *
 * @param rssiMin        이보다 센 신호만 "기준 이상"으로 셈. 복도에서는 벽·문 감쇠 때문에 -85~-90 권장
 * @param wifiIntervalMs Wi-Fi 재측정 간격. 개발자 옵션의 'Wi-Fi 검색 제한'을 꺼야 이 간격대로 새로 측정됨
 * @param bleStaleMs     이 시간 동안 다시 안 보인 BLE 신호는 목록에서 뺌
 * @param wifiStaleMs    이 시간 동안 다시 안 보인 Wi-Fi 신호는 목록에서 뺌
 * @param minRisk        미터·체크포인트에서 볼 최소 위험도 (1 = 전부, 2 = 미분류 제외)
 */
data class PatrolConfig(
    val rssiMin: Int = -88,
    val wifiIntervalMs: Long = 5_000,
    val bleStaleMs: Long = 6_000,
    val wifiStaleMs: Long = 20_000,
    val minRisk: Int = 1,
    val scanBle: Boolean = true,
    val scanWifi: Boolean = true,
    val includeNames: Boolean = false,
)

/** 체크포인트에서 본 신호 1개의 세기 */
data class Reading(val rssi: Int, val risk: Int, val kind: String, val type: SignalType)

/**
 * 문 앞 등 한 지점에서 [RfPatrol.mark] 로 남긴 기록.
 * @param readings 신호원 id(세션 해시) → 그 지점에서의 세기. 지점 간 비교용
 */
data class Checkpoint(
    val label: String,
    val time: Long,
    val strongCount: Int,
    val highRiskCount: Int,
    val maxRssi: Int?,
    val kinds: Map<String, Int>,
    val readings: Map<String, Reading>,
)

/**
 * 지점별 판정: 각 신호원을 "가장 세게 잡힌 지점"에 배정한 결과.
 * @param owned      이 지점에서 가장 세게 잡힌 신호원 수
 * @param ownedHighRisk 그중 위험도 3
 * @param ownedKinds 그 신호원들의 종류별 개수
 * @param maxRssi    이 지점에 배정된 신호 중 최대 세기
 */
data class LocationVerdict(
    val label: String,
    val owned: Int,
    val ownedHighRisk: Int,
    val ownedKinds: Map<String, Int>,
    val maxRssi: Int?,
)

data class PatrolState(
    val running: Boolean = false,
    /** 지금 보이는 기준 이상 신호 (세기는 이동평균), 위험도·세기 순 */
    val live: List<RfDevice> = emptyList(),
    /** 미터에 표시할 신호: 위험도 2 이상 중 가장 센 것, 없으면 전체 중 가장 센 것 */
    val strongest: RfDevice? = null,
    val checkpoints: List<Checkpoint> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** 마지막으로 새로 측정된 Wi-Fi 결과가 몇 초 전인지 */
    val wifiAgeSec: Int? = null,
) {
    /**
     * 지점 비교 결과. 같은 신호가 여러 문 앞에서 잡혔다면 가장 셌던 문 앞에 배정합니다.
     * 배정된 고위험 신호 수 → 신호 수 → 최대 세기 순으로 정렬 (맨 앞이 가장 의심되는 지점).
     */
    fun verdicts(): List<LocationVerdict> {
        val owner = HashMap<String, Pair<String, Reading>>()   // id → (label, reading)
        for (cp in checkpoints) for ((id, rd) in cp.readings) {
            val cur = owner[id]
            if (cur == null || rd.rssi > cur.second.rssi) owner[id] = cp.label to rd
        }
        val byLabel = owner.entries.groupBy({ it.value.first }, { it.value.second })
        return checkpoints.map { it.label }.distinct().map { label ->
            val rs = byLabel[label].orEmpty()
            LocationVerdict(
                label = label,
                owned = rs.size,
                ownedHighRisk = rs.count { it.risk >= 3 },
                ownedKinds = rs.groupingBy { it.kind }.eachCount(),
                maxRssi = rs.maxOfOrNull { it.rssi },
            )
        }.sortedWith(
            compareByDescending<LocationVerdict> { it.ownedHighRisk }
                .thenByDescending { it.owned }
                .thenByDescending { it.maxRssi ?: Int.MIN_VALUE }
        )
    }
}

/**
 * 순찰 모드: 걸으면서 BLE 를 계속 수신하고 Wi-Fi 를 주기적으로 측정합니다.
 * 문 앞마다 [mark] 를 호출하면 지점별 세기를 기록하고, [PatrolState.verdicts] 로 비교합니다.
 *
 * ```kotlin
 * val patrol = RfPatrol(context)
 * patrol.start(lifecycleScope, PatrolConfig(rssiMin = -88))
 * patrol.state.collect { render(it) }
 * patrol.mark("302호")      // 302호 문 앞에서
 * patrol.stop()
 * ```
 * 화면이 꺼지면 안드로이드가 BLE 스캔을 멈출 수 있으니 순찰 중에는 화면을 켜 두세요(keepScreenOn).
 */
class RfPatrol(context: android.content.Context) {
    private val scanner = RfScanner(context)
    private val _state = MutableStateFlow(PatrolState())
    val state: StateFlow<PatrolState> = _state.asStateFlow()

    private var job: Job? = null
    private var ble: BleSource? = null
    private val wifiStore = TrackStore(alpha = 0.6)
    // 순찰 세션 동안 고정: 멈췄다 다시 시작해도 같은 신호원이 같은 id 를 가져야 지점 비교가 됨
    private val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private var config = PatrolConfig()
    @Volatile private var wifiWarning: String? = null
    @Volatile private var lastWifiFresh: Long? = null

    fun missingPermissions() = scanner.missingPermissions()

    fun readiness(config: PatrolConfig = this.config) =
        scanner.readiness(RfScanConfig(scanBle = config.scanBle, scanWifi = config.scanWifi))

    /** 순찰 시작. 이미 실행 중이면 설정만 바꿔 다시 시작합니다. 기록한 체크포인트는 유지됩니다. */
    fun start(scope: CoroutineScope, config: PatrolConfig = PatrolConfig()) {
        stop()
        this.config = config
        val baseWarnings = readiness(config)
        val permitted = scanner.missingPermissions().isEmpty()
        wifiStore.clear()
        wifiWarning = null
        lastWifiFresh = null

        val b = BleSource(salt)
        val ad = scanner.btAdapter
        val bleWarning = if (config.scanBle && permitted && ad != null && ad.isEnabled) b.start(ad) else null
        ble = b

        job = scope.launch {
            val wm = scanner.wifi
            if (config.scanWifi && permitted && wm != null) launch {
                while (isActive) {
                    val out = wifiScanOnce(scanner.ctx, wm, salt, timeoutMs = 4_000, maxAgeMs = config.wifiStaleMs)
                    out.hits.forEach {
                        wifiStore.upsert(it.id, SignalType.WIFI, it.rssi, it.seenAt, it.kind, it.risk, null, it.band, it.ssid)
                    }
                    if (out.fresh) lastWifiFresh = SystemClock.elapsedRealtime()
                    wifiWarning = out.warning
                    delay(config.wifiIntervalMs)
                }
            }
            while (isActive) {
                publish(baseWarnings + listOfNotNull(bleWarning))
                delay(300)
            }
        }
        _state.value = _state.value.copy(running = true, warnings = baseWarnings + listOfNotNull(bleWarning))
    }

    fun stop() {
        job?.cancel()
        job = null
        ble?.stop()
        ble = null
        _state.value = _state.value.copy(running = false, live = emptyList(), strongest = null)
    }

    /** 현재 위치(문 앞 등)의 신호 세기를 [label] 로 기록 */
    fun mark(label: String): Checkpoint {
        val all = currentAll()
        val strong = all.filter { it.rssi >= config.rssiMin && it.risk >= config.minRisk }
        val cp = Checkpoint(
            label = label,
            time = System.currentTimeMillis(),
            strongCount = strong.size,
            highRiskCount = strong.count { it.risk >= 3 },
            maxRssi = strong.maxOfOrNull { it.rssi },
            kinds = strong.groupingBy { it.kind }.eachCount(),
            // 비교를 위해 기준 미만이라도 보이는 신호는 모두 기록 (배정은 '가장 센 지점' 기준)
            readings = all.filter { it.risk >= config.minRisk }
                .associate { it.type.name + ":" + it.id to Reading(it.rssi, it.risk, it.kind, it.type) },
        )
        // 같은 이름으로 다시 기록하면 덮어씀 (문 앞을 다시 지나갈 때)
        _state.value = _state.value.copy(checkpoints = _state.value.checkpoints.filter { it.label != label } + cp)
        return cp
    }

    fun removeCheckpoint(label: String) {
        _state.value = _state.value.copy(checkpoints = _state.value.checkpoints.filter { it.label != label })
    }

    /** 체크포인트를 모두 지움. 새 층·새 교시 순찰을 시작할 때 사용 */
    fun clearCheckpoints() {
        _state.value = _state.value.copy(checkpoints = emptyList())
    }

    private fun currentAll(): List<RfDevice> =
        (ble?.store?.devices(useEma = true, includeNames = config.includeNames, staleMs = config.bleStaleMs).orEmpty()) +
            wifiStore.devices(useEma = true, includeNames = config.includeNames, staleMs = config.wifiStaleMs)

    private fun publish(baseWarnings: List<String>) {
        val all = currentAll()
        val live = all.filter { it.rssi >= config.rssiMin && it.risk >= config.minRisk }
            .sortedWith(compareByDescending<RfDevice> { it.risk }.thenByDescending { it.rssi })
        val strongest = live.filter { it.risk >= 2 }.maxByOrNull { it.rssi } ?: live.maxByOrNull { it.rssi }
        val fresh = lastWifiFresh
        _state.value = _state.value.copy(
            running = true,
            live = live,
            strongest = strongest,
            warnings = baseWarnings + listOfNotNull(wifiWarning),
            wifiAgeSec = fresh?.let { ((SystemClock.elapsedRealtime() - it) / 1000).toInt() },
        )
    }
}
