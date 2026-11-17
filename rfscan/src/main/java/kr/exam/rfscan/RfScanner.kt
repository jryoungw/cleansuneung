package kr.exam.rfscan

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kr.exam.rfscan.internal.BleSource
import kr.exam.rfscan.internal.WifiOutcome
import kr.exam.rfscan.internal.wifiScanOnce
import java.security.SecureRandom

/**
 * 블루투스(BLE) + Wi-Fi 수동 탐지 SDK 진입점.
 *
 * - 수신 전용: 기기에 연결하거나 신호를 보내지 않습니다 (BLE 수동 스캔, Wi-Fi 비콘 목록 조회).
 * - 식별자 비저장: MAC/BSSID 는 세션 salt 해시로만 중복 제거에 쓰고, 이름은 기본적으로 내보내지 않습니다.
 *
 * ```kotlin
 * val scanner = RfScanner(context)
 * if (scanner.missingPermissions().isNotEmpty()) requestPermissions(RfScanner.requiredPermissions())
 * val report = scanner.scan(RfScanConfig(durationMs = 10_000)) { partial -> render(partial) }
 * report.strong   // 기준 세기 이상 신호원
 * ```
 */
class RfScanner(context: Context) {
    internal val ctx = context.applicationContext
    internal val btAdapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    internal val wifi: WifiManager? = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    companion object {
        /** 런타임에 요청해야 하는 권한 */
        @JvmStatic
        fun requiredPermissions(): Array<String> = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()
    }

    fun missingPermissions(): List<String> = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }

    /** 스캔 전에 사용자에게 보여줄 문제점 (비어 있으면 준비 완료) */
    @Suppress("DEPRECATION")
    fun readiness(config: RfScanConfig = RfScanConfig()): List<String> = buildList {
        if (missingPermissions().isNotEmpty()) add("블루투스·위치·주변 기기 권한을 허용해주세요.")
        if (!locationEnabled()) add("위치(GPS)를 켜주세요. 꺼져 있으면 안드로이드가 스캔 결과를 주지 않습니다.")
        if (config.scanBle) {
            if (btAdapter == null) add("이 기기는 블루투스를 지원하지 않습니다.")
            else if (!btAdapter.isEnabled) add("블루투스를 켜주세요.")
        }
        if (config.scanWifi) {
            val w = wifi
            if (w == null) add("이 기기는 Wi-Fi 스캔을 지원하지 않습니다.")
            else if (!w.isWifiEnabled && !w.isScanAlwaysAvailable)
                add("Wi-Fi를 켜주세요. (어디에도 연결할 필요는 없습니다)")
        }
    }

    private fun locationEnabled(): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * 한 번 스캔합니다. [RfScanConfig.durationMs] 동안 BLE 를 수신하고, 같은 시간 안에 Wi-Fi 스캔 결과를 받습니다.
     * @param onUpdate 약 0.5초마다 중간 결과 (호출한 코루틴 컨텍스트에서 실행 — UI 에서 호출하면 메인 스레드)
     */
    suspend fun scan(config: RfScanConfig = RfScanConfig(), onUpdate: ((RfScanReport) -> Unit)? = null): RfScanReport =
        coroutineScope {
            val warnings = readiness(config).toMutableList()
            val startedAt = System.currentTimeMillis()
            val t0 = SystemClock.elapsedRealtime()
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val permitted = missingPermissions().isEmpty()

            val ble = BleSource(salt)
            val ad = btAdapter
            if (config.scanBle && permitted && ad != null && ad.isEnabled) ble.start(ad)?.let { warnings += it }

            val wm = wifi
            val wifiJob = if (config.scanWifi && permitted && wm != null)
                async { wifiScanOnce(ctx, wm, salt, config.durationMs, config.wifiMaxAgeSec * 1000L) } else null

            fun report(finished: Boolean, w: WifiOutcome?) = RfScanReport(
                startedAt = startedAt,
                elapsedMs = SystemClock.elapsedRealtime() - t0,
                finished = finished,
                devices = ble.store.devices(useEma = false, includeNames = config.includeNames) +
                    (w?.hits ?: emptyList()).map {
                        RfDevice(id = it.id, type = SignalType.WIFI, kind = it.kind, risk = it.risk, rssi = it.rssi,
                            band = it.band, name = if (config.includeNames) it.ssid.ifEmpty { null } else null)
                    },
                config = config,
                wifiFresh = w?.fresh ?: false,
                warnings = warnings + listOfNotNull(w?.warning),
            )

            try {
                while (SystemClock.elapsedRealtime() - t0 < config.durationMs) {
                    delay(500)
                    val partialWifi = wifiJob?.takeIf { it.isCompleted }?.await()
                    onUpdate?.invoke(report(false, partialWifi))
                }
            } finally {
                ble.stop()
            }
            val w = wifiJob?.await()
            report(true, w).also { onUpdate?.invoke(it) }
        }
}
