package kr.exam.bledetector

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.exam.rfscan.PatrolConfig
import kr.exam.rfscan.RfDevice
import kr.exam.rfscan.RfPatrol
import kr.exam.rfscan.RfScanConfig
import kr.exam.rfscan.RfScanReport
import kr.exam.rfscan.RfScanner
import kr.exam.rfscan.SignalType

private val Bg = Color(0xFF0F1216)
private val Panel = Color(0xFF181D24)
private val Muted = Color(0xFF8B95A3)
private val Ok = Color(0xFF3FB68B)
private val Warn = Color(0xFFE0A83A)
private val Bad = Color(0xFFE5484D)
private val Accent = Color(0xFF4C8DFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Panel)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { Root() }
            }
        }
    }
}

@Composable
private fun Root() {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = Bg, contentColor = Color.White) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("순찰 모드") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("10초 스캔") })
        }
        when (tab) {
            0 -> PatrolScreen()
            else -> ScanScreen()
        }
    }
}

// ───────────────────────── 공통 ─────────────────────────

private fun riskColor(risk: Int) = when (risk) { 3 -> Bad; 2 -> Warn; else -> Muted }

@Suppress("DEPRECATION")
private fun vibrate(ctx: Context, ms: Long) {
    val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    else v.vibrate(ms)
}

/** 권한·블루투스·Wi-Fi·위치 확인. 문제가 있으면 안내 문구를 돌려주고 해당 설정 화면을 엶 */
private fun checkReady(ctx: Context, problems: List<String>): String? {
    if (problems.isEmpty()) return null
    when {
        problems.any { it.startsWith("위치") } -> ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        problems.any { it.startsWith("블루투스를 켜") } -> ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        problems.any { it.startsWith("Wi-Fi를 켜") } -> ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
    }
    return problems.joinToString("\n")
}

@Composable
private fun PanelCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun ThresholdSlider(value: Float, enabled: Boolean, hint: String, onChange: (Float) -> Unit) {
    PanelCard(Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("감지 기준 세기", color = Color.White, fontSize = 14.sp)
            Text("${value.toInt()} dBm", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = -98f..-45f, enabled = enabled)
        Text(hint, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun DeviceRow(d: RfDevice) {
    val col = riskColor(d.risk)
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (d.type == SignalType.BLE) "BT" else "Wi-Fi", fontSize = 11.sp, color = Color.White,
            modifier = Modifier.clip(RoundedCornerShape(4.dp))
                .background(if (d.type == SignalType.BLE) Color(0xFF3355AA) else Color(0xFF2E7D5B))
                .padding(horizontal = 6.dp, vertical = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(d.kind, color = col, fontSize = 15.sp)
            val sub = listOfNotNull(d.maker, d.band).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, color = Muted, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${d.rssi} dBm", color = Color.White, fontSize = 14.sp)
            SignalBar(d.rssi, col, 70)
        }
    }
    HorizontalDivider(color = Color(0xFF2A313B))
}

@Composable
private fun SignalBar(rssi: Int, color: Color, widthDp: Int?, heightDp: Int = 6) {
    val pct = ((rssi + 100) * 2).coerceIn(4, 100) / 100f
    val w = if (widthDp == null) Modifier.fillMaxWidth() else Modifier.width(widthDp.dp)
    Box(Modifier.padding(top = 4.dp).then(w).height(heightDp.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF232A33))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(pct).background(color))
    }
}

/** "301호" → "302호", "3-1" → "3-2". 숫자가 없으면 그대로 */
private fun nextLabel(label: String): String {
    val m = Regex("^(.*?)(\\d+)(\\D*)$").find(label) ?: return label
    val (pre, num, post) = m.destructured
    return pre + (num.toInt() + 1).toString().padStart(num.length, '0') + post
}

// ───────────────────────── 순찰 모드 ─────────────────────────

private enum class Feedback(val label: String) { VIBRATE("진동"), SOUND("소리"), OFF("끔") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatrolScreen() {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val patrol = remember { RfPatrol(ctx) }
    val state by patrol.state.collectAsState()

    var threshold by remember { mutableFloatStateOf(prefs.getFloat("patrol_threshold", -88f)) }
    var feedback by remember { mutableStateOf(Feedback.valueOf(prefs.getString("feedback", Feedback.VIBRATE.name)!!)) }
    var label by remember { mutableStateOf(prefs.getString("next_label", "301호") ?: "301호") }
    var message by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (patrol.missingPermissions().isNotEmpty()) message = "권한이 거부되었습니다. 설정 > 앱 > 전파 탐지 > 권한에서 허용해주세요."
    }

    DisposableEffect(Unit) { onDispose { patrol.stop() } }
    // 화면이 꺼지면 BLE 스캔이 멈출 수 있어 순찰 중에는 화면을 켜 둠
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    // 가까워질수록 빨라지는 진동·비프 (금속탐지기처럼)
    LaunchedEffect(state.running, feedback) {
        if (!state.running || feedback == Feedback.OFF) return@LaunchedEffect
        val tone = if (feedback == Feedback.SOUND) ToneGenerator(AudioManager.STREAM_MUSIC, 70) else null
        try {
            while (true) {
                val s = patrol.state.value.strongest
                if (s == null) { delay(300); continue }
                // -98 dBm → 1.5초 간격, -45 dBm → 0.1초 간격
                val t = ((s.rssi + 98).coerceIn(0, 53)) / 53f
                val interval = (1500 - 1400 * t).toLong()
                if (tone != null) tone.startTone(if (s.risk >= 3) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 60)
                else vibrate(ctx, if (s.risk >= 3) 70 else 35)
                delay(interval)
            }
        } finally {
            tone?.release()
        }
    }

    fun config() = PatrolConfig(rssiMin = threshold.toInt())

    fun toggle() {
        message = null
        if (state.running) { patrol.stop(); return }
        if (patrol.missingPermissions().isNotEmpty()) { launcher.launch(RfScanner.requiredPermissions()); return }
        checkReady(ctx, patrol.readiness(config()))?.let { message = it; return }
        patrol.start(scope, config())
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("복도를 걸으며 계속 감지합니다. 교실 문 앞마다 「기록」을 누르면 어느 교실에서 가장 센지 비교합니다.",
            fontSize = 13.sp, color = Muted, modifier = Modifier.padding(bottom = 12.dp))

        Button(onClick = { toggle() }, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (state.running) Color(0xFF3A3F47) else Accent)) {
            Text(if (state.running) "순찰 멈춤" else "순찰 시작", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        message?.let { Text(it, color = Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        if (state.running) state.warnings.forEach { Text(it, color = Warn, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }

        // 미터
        PanelCard(Modifier.padding(top = 12.dp)) {
            val s = state.strongest
            val col = s?.let { riskColor(it.risk) } ?: Ok
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("가장 센 신호", color = Muted, fontSize = 12.sp)
                    Text(s?.kind ?: if (state.running) "기준 이상 신호 없음" else "대기 중", color = col,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (s != null) Text(if (s.type == SignalType.BLE) "블루투스" else "Wi-Fi ${s.band ?: ""}", color = Muted, fontSize = 12.sp)
                }
                Text(s?.let { "${it.rssi}" } ?: "–", color = col, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold)
                Text(" dBm", color = Muted, fontSize = 13.sp)
            }
            SignalBar(s?.rssi ?: -100, col, null, 14)
            Text("기준 이상 ${state.live.size}개 · 블루투스 ${state.live.count { it.type == SignalType.BLE }} · Wi-Fi ${state.live.count { it.type == SignalType.WIFI }}" +
                (state.wifiAgeSec?.let { " · Wi-Fi ${it}초 전 측정" } ?: ""), color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp))
        }

        // 체크포인트 기록
        PanelCard(Modifier.padding(top = 12.dp)) {
            Text("문 앞에서 기록", color = Color.White, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(label, { label = it }, singleLine = true, modifier = Modifier.weight(1f),
                    label = { Text("위치 (예: 302호)") })
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (label.isBlank()) return@Button
                    patrol.mark(label.trim())
                    vibrate(ctx, 20)
                    label = nextLabel(label.trim())
                    prefs.edit().putString("next_label", label).apply()
                }, enabled = state.running, modifier = Modifier.height(56.dp)) { Text("기록") }
            }
            Text("기록하면 위치 번호가 자동으로 1 올라갑니다. 같은 위치를 다시 기록하면 덮어씁니다.", color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
        }

        // 비교 결과
        if (state.checkpoints.isNotEmpty()) {
            val verdicts = state.verdicts()
            PanelCard(Modifier.padding(top = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("위치별 비교 (${state.checkpoints.size}곳)", color = Color.White, fontSize = 14.sp)
                    TextButton(onClick = { patrol.clearCheckpoints() }) { Text("초기화", color = Muted) }
                }
                Text("각 신호를 가장 세게 잡힌 위치에 배정했습니다. 위쪽일수록 의심도가 높습니다.", color = Muted, fontSize = 12.sp)
                verdicts.forEachIndexed { i, v ->
                    val top = i == 0 && v.owned > 0
                    val col = when { v.ownedHighRisk > 0 -> Bad; v.owned > 0 -> Warn; else -> Ok }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(v.label + if (top) "  ← 가장 의심" else "", color = if (top) col else Color.White,
                                fontSize = 16.sp, fontWeight = if (top) FontWeight.Bold else FontWeight.Normal)
                            Text(if (v.owned == 0) "이 위치에서 가장 센 신호 없음"
                                else v.ownedKinds.entries.joinToString(", ") { "${it.key} ${it.value}" },
                                color = Muted, fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${v.owned}개", color = col, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            v.maxRssi?.let { Text("최대 $it dBm", color = Muted, fontSize = 11.sp) }
                        }
                    }
                    HorizontalDivider(color = Color(0xFF2A313B))
                }
            }
        }

        // 지금 보이는 신호
        if (state.running && state.live.isNotEmpty()) {
            PanelCard(Modifier.padding(top = 12.dp)) {
                Text("지금 보이는 신호", color = Color.White, fontSize = 14.sp)
                state.live.take(12).forEach { DeviceRow(it) }
            }
        }

        ThresholdSlider(threshold, enabled = true,
            hint = "복도에서는 벽·문 때문에 신호가 약해지므로 -85~-90 dBm을 권장합니다. 바꾸면 순찰을 다시 시작합니다.") {
            threshold = it
            prefs.edit().putFloat("patrol_threshold", it).apply()
        }
        LaunchedEffect(threshold.toInt()) {
            if (patrol.state.value.running) { delay(400); patrol.start(scope, config()) }
        }

        PanelCard(Modifier.padding(top = 12.dp)) {
            Text("신호 알림", color = Color.White, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Feedback.entries.forEach { f ->
                    FilterChip(selected = feedback == f, onClick = {
                        feedback = f; prefs.edit().putString("feedback", f.name).apply()
                    }, label = { Text(f.label) })
                }
            }
            Text("시험 중 복도에서는 진동을 권장합니다. 신호가 셀수록 빠르게, 고위험 신호는 길게 울립니다.", color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        Text("Wi-Fi를 자주 새로 측정하려면 개발자 옵션 → 'Wi-Fi 검색 제한'을 꺼주세요.", color = Muted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
    }
}

// ───────────────────────── 10초 스캔 ─────────────────────────

@Composable
private fun ScanScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanner = remember { RfScanner(ctx) }
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    var report by remember { mutableStateOf<RfScanReport?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var threshold by remember { mutableFloatStateOf(prefs.getFloat("threshold", -75f)) }
    var message by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (scanner.missingPermissions().isNotEmpty()) message = "권한이 거부되었습니다. 설정 > 앱 > 전파 탐지 > 권한에서 허용해주세요."
    }

    fun startScan() {
        message = null
        if (scanner.missingPermissions().isNotEmpty()) { launcher.launch(RfScanner.requiredPermissions()); return }
        checkReady(ctx, scanner.readiness())?.let { message = it; return }
        report = null
        scanning = true
        scope.launch {
            val t = threshold.toInt()
            val result = scanner.scan(RfScanConfig(durationMs = 10_000, bleRssiMin = t, wifiRssiMin = t)) { report = it }
            report = result
            scanning = false
            if (result.warnings.isNotEmpty()) message = result.warnings.joinToString("\n")
            if (result.strong.isNotEmpty()) vibrate(ctx, 400)
        }
    }

    val strong = report?.strong.orEmpty()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("한 자리에서 10초간 주변 블루투스·Wi-Fi 신호를 감지합니다. 기기 이름·식별자는 저장하지 않습니다.",
            fontSize = 13.sp, color = Muted, modifier = Modifier.padding(bottom = 12.dp))

        Button(onClick = { startScan() }, enabled = !scanning,
            modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(16.dp)) {
            val left = report?.let { ((it.config.durationMs - it.elapsedMs) / 1000).coerceAtLeast(0) } ?: 10
            Text(if (scanning) "감지 중… ${left}초" else if (report == null) "감지 시작" else "다시 감지",
                fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        message?.let { Text(it, color = Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }

        ThresholdSlider(threshold, enabled = !scanning,
            hint = "오른쪽으로 갈수록 가까운 신호만 셉니다. 학교 Wi-Fi를 모두 끈 상태라면 교실 안 Wi-Fi 신호는 모두 의심 대상입니다.") {
            threshold = it; prefs.edit().putFloat("threshold", it).apply()
        }

        val r = report
        if (r != null) {
            PanelCard(Modifier.padding(top = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("기준 이상 신호", color = Color.White)
                        Text("블루투스 ${strong.count { it.type == SignalType.BLE }} · Wi-Fi ${strong.count { it.type == SignalType.WIFI }}",
                            color = Muted, fontSize = 12.sp)
                    }
                    Text("${strong.size}", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold,
                        color = when { strong.isEmpty() -> Ok; r.highRisk.isNotEmpty() -> Bad; else -> Warn })
                }
                Text("전체 ${r.devices.size}개" + if (!r.wifiFresh && r.finished) " · Wi-Fi는 최근 캐시 결과" else "",
                    color = Muted, fontSize = 12.sp)
                if (strong.isEmpty() && r.finished)
                    Text("기준 이상 세기의 신호가 없습니다.", color = Ok, modifier = Modifier.padding(vertical = 8.dp))
                strong.forEach { DeviceRow(it) }
            }
        }
    }
}
