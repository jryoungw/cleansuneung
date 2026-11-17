package kr.exam.rfscan.internal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kr.exam.rfscan.Classifier
import kr.exam.rfscan.RfDevice
import kr.exam.rfscan.SignalType
import java.security.MessageDigest
import kotlin.math.roundToInt

internal fun hashId(salt: ByteArray, addr: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(salt)
    md.update(addr.uppercase().toByteArray())
    return md.digest().take(8).joinToString("") { "%02x".format(it) }
}

/** 신호원 1개의 추적 상태. 세기는 지수이동평균(EMA)으로 부드럽게 만들어 순찰 중 흔들림을 줄임 */
internal class Track(
    val id: String,
    val type: SignalType,
    var kind: String,
    var risk: Int,
    var maker: String?,
    var band: String?,
    var rawName: String,
    var rssiEma: Double,
    var rssiMax: Int,
    var lastSeen: Long,      // SystemClock.elapsedRealtime() ms
    var packets: Int,
) {
    fun toDevice(useEma: Boolean, includeNames: Boolean) = RfDevice(
        id = id, type = type, kind = kind, risk = risk,
        rssi = if (useEma) rssiEma.roundToInt() else rssiMax,
        maker = maker, band = band,
        name = if (includeNames) rawName.ifEmpty { null } else null,
        packets = packets,
    )
}

internal class TrackStore(private val alpha: Double = 0.35) {
    private val map = LinkedHashMap<String, Track>()

    fun upsert(
        id: String, type: SignalType, rssi: Int, seenAt: Long,
        kind: String, risk: Int, maker: String?, band: String?, rawName: String,
    ) = synchronized(map) {
        val t = map[id]
        if (t == null) {
            map[id] = Track(id, type, kind, risk, maker, band, rawName, rssi.toDouble(), rssi, seenAt, 1)
            return@synchronized
        }
        if (risk > t.risk) { t.kind = kind; t.risk = risk }
        if (maker != null) t.maker = maker
        if (band != null) t.band = band
        if (rawName.isNotEmpty()) t.rawName = rawName
        t.rssiMax = maxOf(t.rssiMax, rssi)
        if (seenAt > t.lastSeen) {   // 같은 Wi-Fi 캐시 결과가 반복될 때 평균이 왜곡되지 않게
            t.rssiEma = alpha * rssi + (1 - alpha) * t.rssiEma
            t.lastSeen = seenAt
            t.packets++
        }
    }

    fun rawName(id: String): String = synchronized(map) { map[id]?.rawName.orEmpty() }

    /** @param staleMs 지정하면 그 시간 안에 다시 보인 신호원만 */
    fun devices(useEma: Boolean, includeNames: Boolean, staleMs: Long? = null): List<RfDevice> = synchronized(map) {
        val now = SystemClock.elapsedRealtime()
        map.values.filter { staleMs == null || now - it.lastSeen <= staleMs }
            .map { it.toDevice(useEma, includeNames) }
    }

    fun clear() = synchronized(map) { map.clear() }
}

/** BLE 수동 스캔 → TrackStore */
internal class BleSource(private val salt: ByteArray, val store: TrackStore = TrackStore()) {
    private var adapter: BluetoothAdapter? = null
    private var cb: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun start(ad: BluetoothAdapter): String? {
        val scanner = ad.bluetoothLeScanner ?: return "블루투스 스캐너를 열 수 없습니다. 블루투스를 껐다 켜보세요."
        adapter = ad
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .apply {
                if (ad.isLeExtendedAdvertisingSupported) { // 블루투스 5 확장 광고까지
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
            override fun onScanFailed(errorCode: Int) {}
        }
        cb = callback
        return try {
            scanner.startScan(null, settings, callback); null
        } catch (e: SecurityException) {
            cb = null
            "블루투스 스캔 권한이 없습니다."
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val c = cb ?: return
        try { adapter?.bluetoothLeScanner?.stopScan(c) } catch (_: Exception) {}
        cb = null
    }

    private fun handle(r: ScanResult) {
        val rec = r.scanRecord
        val mfr = HashMap<Int, ByteArray>()
        rec?.manufacturerSpecificData?.let { sa -> for (i in 0 until sa.size()) mfr[sa.keyAt(i)] = sa.valueAt(i) }
        val id = hashId(salt, r.device.address)
        val advName = rec?.deviceName.orEmpty()   // device.name 은 별도 권한이 필요해 광고 이름만 사용
        val (kind, risk) = Classifier.classifyBle(advName.ifEmpty { store.rawName(id) }, mfr)
        store.upsert(
            id = id, type = SignalType.BLE, rssi = r.rssi, seenAt = SystemClock.elapsedRealtime(),
            kind = kind, risk = risk, maker = mfr.keys.firstOrNull()?.let(Classifier::companyName),
            band = null, rawName = advName,
        )
    }
}

internal class WifiHit(
    val id: String, val rssi: Int, val seenAt: Long, val kind: String, val risk: Int, val band: String, val ssid: String,
)

internal class WifiOutcome(val hits: List<WifiHit>, val fresh: Boolean, val warning: String?)

/** Wi-Fi 비콘 목록 1회 조회. 안드로이드 제한(포그라운드 2분에 4회)에 걸리면 최근 캐시 결과를 돌려줌 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal suspend fun wifiScanOnce(
    ctx: Context, wm: WifiManager, salt: ByteArray, timeoutMs: Long, maxAgeMs: Long,
): WifiOutcome {
    val done = CompletableDeferred<Boolean>()
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            done.complete(i?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) == true)
        }
    }
    ContextCompat.registerReceiver(ctx, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
        ContextCompat.RECEIVER_NOT_EXPORTED)
    try {
        val started = try { wm.startScan() } catch (e: SecurityException) { false }
        val fresh = started && (withTimeoutOrNull(timeoutMs) { done.await() } ?: false)
        val nowMs = SystemClock.elapsedRealtime()
        val results = try { wm.scanResults } catch (e: SecurityException) {
            return WifiOutcome(emptyList(), false, "Wi-Fi 스캔 권한(위치·주변 기기)이 없습니다.")
        }
        val hits = results
            .filter { nowMs - it.timestamp / 1000 <= maxAgeMs }
            .groupBy { it.BSSID.orEmpty() }
            .map { (bssid, rs) ->
                val r = rs.maxBy { it.level }
                val ssid = r.SSID.orEmpty().removeSurrounding("\"").let { if (it == "<unknown ssid>") "" else it }
                val (kind, risk) = Classifier.classifyWifi(ssid, bssid, r.capabilities.orEmpty())
                WifiHit(hashId(salt, bssid), r.level, r.timestamp / 1000, kind, risk, Classifier.wifiBand(r.frequency), ssid)
            }
        val warning = when {
            fresh -> null
            !started -> "Wi-Fi 스캔 횟수 제한(2분에 4회)에 걸려 최근 결과를 썼습니다. 개발자 옵션 → 'Wi-Fi 검색 제한'을 끄면 매번 새로 측정합니다."
            else -> "Wi-Fi 새 측정이 시간 안에 끝나지 않아 최근 결과를 썼습니다."
        }
        return WifiOutcome(hits, fresh, warning)
    } finally {
        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
