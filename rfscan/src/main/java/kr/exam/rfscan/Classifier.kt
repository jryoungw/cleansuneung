package kr.exam.rfscan

/** 신호원 종류 판별. 이름·제조사·MAC 특성만 보고 추정합니다(확정 아님). */
object Classifier {

    // ---------------- BLE ----------------

    /** Bluetooth SIG Company Identifier */
    private val COMPANY = mapOf(
        0x004C to "Apple", 0x0075 to "Samsung", 0x00E0 to "Google", 0x0006 to "Microsoft",
        0x0157 to "Huami/Amazfit", 0x038F to "Xiaomi", 0x027D to "Huawei", 0x0087 to "Garmin",
        0x00D2 to "Bose", 0x012D to "Sony", 0x01AB to "Meta", 0x0059 to "Nordic",
        0x02E5 to "Espressif", 0x0131 to "Cypress", 0x000D to "Texas Instruments",
    )

    private data class Rule(val re: Regex, val kind: String, val risk: Int)

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val BLE_NAME_RULES = listOf(
        Rule(rx("airpods|buds|earbud|이어|headset|wf-|wh-|bose|beats|jabra|qcy|soundcore"), "이어폰·헤드셋", 3),
        Rule(rx("glass|ray-?ban|meta|spectacles|안경"), "스마트안경", 3),
        Rule(rx("cam|esp|arduino|hc-0|ble_|nrf|dsd tech|jdy-|at-09|hm-1"), "자작 보드·카메라 모듈", 3),
        Rule(rx("watch|band|fit|garmin|amazfit|워치"), "스마트워치·밴드", 2),
        Rule(rx("iphone|galaxy|pixel|phone|sm-|폰"), "휴대폰", 2),
    )

    fun companyName(id: Int): String = COMPANY[id] ?: String.format("0x%04X", id)

    fun classifyBle(name: String, mfr: Map<Int, ByteArray>): Pair<String, Int> {
        BLE_NAME_RULES.firstOrNull { it.re.containsMatchIn(name) }?.let { return it.kind to it.risk }
        // Apple Continuity 메시지 타입 (제조사 데이터 첫 바이트)
        mfr[0x004C]?.let { d ->
            return when (d.firstOrNull()?.toInt()?.and(0xFF)) {
                0x07 -> "AirPods·Beats 이어폰" to 3        // Proximity Pairing
                0x12 -> "Apple 분실물 신호(AirTag 등)" to 2  // Find My
                0x10 -> "iPhone·iPad·Watch" to 2            // Nearby Info
                0x0C, 0x0F -> "Apple 기기(Handoff 등)" to 2
                0x02 -> "iBeacon(고정 비콘 가능)" to 1
                else -> "Apple 기기" to 2
            }
        }
        return when {
            mfr.containsKey(0x02E5) || mfr.containsKey(0x0059) || mfr.containsKey(0x0131) -> "자작 보드 가능성" to 3
            mfr.containsKey(0x01AB) -> "Meta 기기(스마트안경 가능)" to 3
            mfr.containsKey(0x0075) -> "Samsung 기기" to 2
            mfr.containsKey(0x0006) -> "Windows 기기" to 2
            mfr.containsKey(0x00E0) -> "Google·안드로이드 기기" to 2
            else -> "미분류 블루투스 기기" to 1
        }
    }

    // ---------------- Wi-Fi ----------------

    private val CAMERA_SSID = rx("cam|ipc|hdwifi|wificam|lookcam|v380|yoosee|camhi|mini[_-]?dv|spy|^yi[-_]|^xm[-_]")
    private val HOTSPOT_SSID = rx("iphone|ipad|galaxy|android|androidap|sm-|pixel|redmi|xiaomi|poco|oneplus|huawei|핫스팟|hotspot|의 |'s ")

    fun classifyWifi(ssid: String, bssid: String, capabilities: String): Pair<String, Int> = when {
        CAMERA_SSID.containsMatchIn(ssid) -> "무선 카메라 추정" to 3
        ssid.startsWith("DIRECT-") -> "Wi-Fi Direct(카메라·기기 직접 연결)" to 3
        HOTSPOT_SSID.containsMatchIn(ssid) -> "휴대폰 핫스팟 추정" to 3
        ssid.isBlank() -> "숨김 네트워크(핫스팟 가능)" to 2
        isLocallyAdministered(bssid) -> "임의 MAC 네트워크(핫스팟 가능)" to 2
        else -> "일반 공유기·AP" to 1
    }

    /** MAC 첫 바이트의 두 번째 비트 = 로컬 관리 주소. 폰 핫스팟·Wi-Fi Direct 는 대부분 임의 MAC 을 씀 */
    fun isLocallyAdministered(mac: String): Boolean =
        mac.take(2).toIntOrNull(16)?.let { it and 0x02 != 0 } ?: false

    fun wifiBand(freqMHz: Int): String = when (freqMHz) {
        in 2400..2500 -> "2.4GHz"
        in 4900..5900 -> "5GHz"
        in 5925..7125 -> "6GHz"
        else -> "${freqMHz}MHz"
    }
}
