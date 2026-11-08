package com.example.takeyourmark

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.example.takeyourmark.ui.theme.TakeYourMarkTheme
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TakeYourMarkTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    StartPracticeScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

// --- 레코드 데이터 모델 및 저장소 ---
data class StartRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestampFormatted: String, // yyyyMMdd(E) HH:mm:ss
    val splitTimes: List<String>,   // 최대 8개 기록
    var memo: String = ""
)

class RecordRepository(context: Context) {
    private val prefs = context.getSharedPreferences("take_your_mark_db", Context.MODE_PRIVATE)

    fun getAllRecords(): List<StartRecord> {
        val jsonString = prefs.getString("records", "[]") ?: "[]"
        val list = mutableListOf<StartRecord>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", UUID.randomUUID().toString())
                val timestamp = obj.optString("timestampFormatted", "")
                val memo = obj.optString("memo", "")
                val splitsJson = obj.optJSONArray("splitTimes")
                val splits = mutableListOf<String>()
                if (splitsJson != null) {
                    for (j in 0 until splitsJson.length()) {
                        splits.add(splitsJson.getString(j))
                    }
                }
                list.add(StartRecord(id, timestamp, splits, memo))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun addRecord(record: StartRecord) {
        val records = getAllRecords().toMutableList()
        records.add(0, record) // 최신 기록이 상단에 위치
        saveAll(records)
    }

    fun updateMemo(id: String, newMemo: String) {
        val records = getAllRecords().toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index != -1) {
            records[index] = records[index].copy(memo = newMemo)
            saveAll(records)
        }
    }

    fun deleteRecord(id: String) {
        val records = getAllRecords().toMutableList()
        records.removeAll { it.id == id }
        saveAll(records)
    }

    private fun saveAll(records: List<StartRecord>) {
        val jsonArray = JSONArray()
        for (r in records) {
            val obj = JSONObject()
            obj.put("id", r.id)
            obj.put("timestampFormatted", r.timestampFormatted)
            obj.put("memo", r.memo)
            val splitsArr = JSONArray()
            r.splitTimes.forEach { splitsArr.put(it) }
            obj.put("splitTimes", splitsArr)
            jsonArray.put(obj)
        }
        prefs.edit {
            putString("records", jsonArray.toString())
        }
    }
}

// --- 자동 반복 설정 데이터 ---
enum class AutoStopMode {
    MANUAL_CLICK,    // 수동 조작 시 자동 정지 (디폴트)
    EXPLICIT_DISABLE // 세팅에서 직접 해제할 때까지 계속
}

data class AutoSettings(
    val isEnabled: Boolean = false,
    val minTimeSec: Float = 2.0f,
    val maxTimeSec: Float = 3.0f,
    val isRandom: Boolean = true,
    val nextPrepareIntervalSec: Float = 10.0f, // 다음 준비까지의 시간 (기본 10초)
    val stopMode: AutoStopMode = AutoStopMode.MANUAL_CLICK
)

// --- 연습 상태 ---
enum class PracticeState {
    READY,       // 준비 전 또는 대기 상태
    WAITING,     // 준비 구령 후 출발 대기 중
    RUNNING      // 출발 비프음 발생 후
}

// --- 독립 타이머 레이블 데이터 상태 ---
class TimerLabelState(val labelIndex: Int) {
    var isHighlighted by mutableStateOf(false)
    var isRunning by mutableStateOf(false)
    var startTimestampMs by mutableLongStateOf(0L)
    var currentTimerMillis by mutableLongStateOf(0L)
    val splitTimes = mutableStateListOf<String>()
    var startTimestampFormatted by mutableStateOf("")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartPracticeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { RecordRepository(context) }

    var practiceState by remember { mutableStateOf(PracticeState.READY) }
    var elapsedTimeMillis by remember { mutableLongStateOf(0L) }

    // 자동 설정 상태
    var autoSettings by remember { mutableStateOf(AutoSettings()) }

    // 다이얼로그 상태
    var showRulesDialog by remember { mutableStateOf(false) }
    var showRecordsDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // 활성화된 타이머 인덱스 (-1: 시작 전, 0: #1, 1: #2, 2: #3)
    var activeTimerIndex by remember { mutableIntStateOf(-1) }

    // 3개 타이머 레이블
    val timerLabels = remember {
        mutableStateListOf(
            TimerLabelState(1),
            TimerLabelState(2),
            TimerLabelState(3)
        )
    }

    // MediaPlayer 준비
    val preparePlayer = remember { MediaPlayer.create(context, R.raw.take_your_mark) }
    val beepPlayer = remember { MediaPlayer.create(context, R.raw.take_your_mark_beep) }

    DisposableEffect(Unit) {
        onDispose {
            preparePlayer?.release()
            beepPlayer?.release()
        }
    }

    // 독립 스톱워치 틱 (모든 가동 중인 타이머들의 시간을 10ms 단위로 실시간 갱신)
    LaunchedEffect(Unit) {
        while (true) {
            delay(10L)
            val now = System.currentTimeMillis()
            timerLabels.forEach { label ->
                if (label.isRunning) {
                    label.currentTimerMillis = now - label.startTimestampMs
                }
            }
        }
    }

    // 자동 반복 중 1초 간격 부드러운 깜빡임 애니메이션
    val infiniteTransition = rememberInfiniteTransition(label = "BlinkAnimation")
    val animatedAlpha by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BlinkAlpha"
    )

    // 함수: 정지 및 DB 저장
    val stopAndSaveLabel = { label: TimerLabelState ->
        if (label.isRunning) {
            label.isRunning = false
            val record = StartRecord(
                timestampFormatted = label.startTimestampFormatted.ifEmpty {
                    SimpleDateFormat("yyyyMMdd(E) HH:mm:ss", Locale.KOREAN).format(Date())
                },
                splitTimes = label.splitTimes.toList(),
                memo = "타이머 #${label.labelIndex} 기록"
            )
            repository.addRecord(record)
        }
    }

    // 함수: [준비] 실행 (순환 하이라이트 지정)
    val triggerPrepare = {
        preparePlayer?.apply {
            if (isPlaying) seekTo(0) else start()
        }

        // 다음 인덱스 순환 (0 -> 1 -> 2 -> 0 ...)
        val nextIndex = if (activeTimerIndex == -1) 0 else (activeTimerIndex + 1) % 3

        // 순환되어 자신의 타이머가 다시 하이라이트 되면 기존 타이머 정지(00:00.00) 후 DB 저장
        val targetLabel = timerLabels[nextIndex]
        if (targetLabel.isRunning) {
            stopAndSaveLabel(targetLabel)
            // 다음 회차를 위한 시간 및 슬롯 초기화
            targetLabel.currentTimerMillis = 0L
            targetLabel.splitTimes.clear()
            targetLabel.startTimestampFormatted = ""
        }

        // 하이라이트 상태 업데이트
        timerLabels.forEachIndexed { idx, label ->
            label.isHighlighted = (idx == nextIndex)
        }
        activeTimerIndex = nextIndex
        practiceState = PracticeState.WAITING
    }

    // 함수: [출발] 실행
    val triggerStart = {
        beepPlayer?.apply {
            if (isPlaying) seekTo(0) else start()
        }

        if (activeTimerIndex in 0..2) {
            val targetLabel = timerLabels[activeTimerIndex]
            val sdf = SimpleDateFormat("yyyyMMdd(E) HH:mm:ss", Locale.KOREAN)

            targetLabel.startTimestampMs = System.currentTimeMillis()
            targetLabel.currentTimerMillis = 0L
            targetLabel.splitTimes.clear()
            targetLabel.startTimestampFormatted = sdf.format(Date())
            targetLabel.isRunning = true
        }

        practiceState = PracticeState.RUNNING
    }

    // 구령 후 대기 중 타이머 (WAITING 상태 시간)
    LaunchedEffect(practiceState) {
        if (practiceState == PracticeState.WAITING) {
            val startTime = System.currentTimeMillis()
            while (practiceState == PracticeState.WAITING) {
                delay(10L)
                elapsedTimeMillis = System.currentTimeMillis() - startTime
            }
        }
    }

    // --- 자동 반복 제어 코루틴 ---
    LaunchedEffect(autoSettings.isEnabled, practiceState) {
        if (autoSettings.isEnabled) {
            when (practiceState) {
                PracticeState.READY -> {
                    delay(1500L) // READY 진입 후 1.5초 뒤 자동 준비
                    if (autoSettings.isEnabled && practiceState == PracticeState.READY) {
                        triggerPrepare()
                    }
                }
                PracticeState.WAITING -> {
                    val minMs = (autoSettings.minTimeSec * 1000).toLong()
                    val maxMs = (autoSettings.maxTimeSec * 1000).toLong().coerceAtLeast(minMs)

                    val targetDelayMs = if (autoSettings.isRandom && maxMs > minMs) {
                        Random.nextLong(minMs, maxMs)
                    } else {
                        minMs
                    }

                    delay(targetDelayMs)
                    if (autoSettings.isEnabled && practiceState == PracticeState.WAITING) {
                        triggerStart()
                    }
                }
                PracticeState.RUNNING -> {
                    val nextPrepareDelayMs = (autoSettings.nextPrepareIntervalSec * 1000).toLong()
                    delay(nextPrepareDelayMs)
                    if (autoSettings.isEnabled && practiceState == PracticeState.RUNNING) {
                        triggerPrepare()
                    }
                }
            }
        }
    }

    // 3초 경과 상태 체크
    val isWarningColor = practiceState == PracticeState.WAITING && elapsedTimeMillis >= 3000L

    // 메인 컨트롤 카드의 배경색 애니메이션 (3초 경과 시 카드만 붉은색으로 연출)
    val cardBackgroundColor by animateColorAsState(
        targetValue = if (isWarningColor) Color(0xFFFFCDD2) else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "CardWarningColorAnimation"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 상단 Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Take Your Mark",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // ⚙️ 설정 아이콘
                    FilledTonalIconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Text(text = "⚙️", fontSize = 16.sp)
                    }

                    // 📋 레코드 아이콘
                    FilledTonalIconButton(
                        onClick = { showRecordsDialog = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Text(text = "📋", fontSize = 16.sp)
                    }

                    // ? 규정 아이콘
                    FilledTonalIconButton(
                        onClick = { showRulesDialog = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Text(
                            text = "?",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 준비/출발 메인 컨트롤 카드 (3초 경과 시 해당 카드만 붉은 배경 연출)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = cardBackgroundColor
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 시간 표시 (항상 00:00.00 형식 통일)
                    val mainTimerFormatted = if (practiceState == PracticeState.WAITING) {
                        String.format(
                            Locale.getDefault(),
                            "%02d:%02d.%02d",
                            (elapsedTimeMillis / 1000) / 60,
                            (elapsedTimeMillis / 1000) % 60,
                            (elapsedTimeMillis % 1000) / 10
                        )
                    } else {
                        "00:00.00"
                    }

                    Text(
                        text = mainTimerFormatted,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isWarningColor) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                    )

                    // 준비 & 출발 버튼 (엄격한 교대: 준비 후엔 출발만 활성화, 출발 후엔 준비만 활성화)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // "준비" 버튼 (practiceState != WAITING 일 때만 활성화)
                        val isPrepareBlinking = autoSettings.isEnabled && (practiceState == PracticeState.READY || practiceState == PracticeState.RUNNING)
                        Button(
                            onClick = {
                                if (autoSettings.isEnabled && autoSettings.stopMode == AutoStopMode.MANUAL_CLICK) {
                                    autoSettings = autoSettings.copy(isEnabled = false)
                                }
                                triggerPrepare()
                            },
                            enabled = practiceState != PracticeState.WAITING,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.alpha(if (isPrepareBlinking) animatedAlpha else 1.0f)
                        ) {
                            Text("준비", fontSize = 14.sp)
                        }

                        // "출발" 버튼 (practiceState == WAITING 일 때만 활성화)
                        val isStartBlinking = autoSettings.isEnabled && practiceState == PracticeState.WAITING
                        Button(
                            onClick = {
                                if (autoSettings.isEnabled && autoSettings.stopMode == AutoStopMode.MANUAL_CLICK) {
                                    autoSettings = autoSettings.copy(isEnabled = false)
                                }
                                triggerStart()
                            },
                            enabled = practiceState == PracticeState.WAITING,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.alpha(if (isStartBlinking) animatedAlpha else 1.0f)
                        ) {
                            Text("출발", fontSize = 14.sp)
                        }
                    }
                }
            }

            // 하단: 순환 동작하는 3개 타이머 레이블
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                timerLabels.forEach { labelState ->
                    TimerLabelCard(
                        labelState = labelState,
                        onRecordClick = {
                            val formattedTime = String.format(
                                Locale.getDefault(),
                                "%02d:%02d.%02d",
                                (labelState.currentTimerMillis / 1000) / 60,
                                (labelState.currentTimerMillis / 1000) % 60,
                                (labelState.currentTimerMillis % 1000) / 10
                            )
                            if (labelState.splitTimes.size < 8) {
                                labelState.splitTimes.add(formattedTime)
                            }
                        },
                        onStopClick = {
                            stopAndSaveLabel(labelState)
                        }
                    )
                }
            }
        }
    }

    // ⚙️ 설정 다이얼로그
    if (showSettingsDialog) {
        SettingsDialog(
            settings = autoSettings,
            onSettingsChanged = { autoSettings = it },
            onDismiss = { showSettingsDialog = false }
        )
    }

    // 📋 레코드 DB 다이얼로그
    if (showRecordsDialog) {
        RecordListDialog(
            repository = repository,
            onDismiss = { showRecordsDialog = false }
        )
    }

    // ? 출발 규정 안내 다이얼로그
    if (showRulesDialog) {
        RulesDialog(onDismiss = { showRulesDialog = false })
    }
}

// --- 개별 타이머 레이블 컴포넌트 ---
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimerLabelCard(
    labelState: TimerLabelState,
    onRecordClick: () -> Unit,
    onStopClick: () -> Unit
) {
    val formattedTime = String.format(
        Locale.getDefault(),
        "%02d:%02d.%02d",
        (labelState.currentTimerMillis / 1000) / 60,
        (labelState.currentTimerMillis / 1000) % 60,
        (labelState.currentTimerMillis % 1000) / 10
    )

    // 하이라이트 시 테두리 및 배경 강조
    val cardBorderModifier = if (labelState.isHighlighted) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
    } else {
        Modifier
    }

    val containerColor = if (labelState.isHighlighted) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(cardBorderModifier),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (labelState.isHighlighted) 4.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "타이머 #${labelState.labelIndex}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (labelState.isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formattedTime,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (labelState.isRunning) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 기록 버튼
                    Button(
                        onClick = onRecordClick,
                        enabled = labelState.isRunning && labelState.splitTimes.size < 8,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("기록", fontSize = 11.sp)
                    }

                    // 정지 버튼 (정지 아이콘 "⏹")
                    Button(
                        onClick = onStopClick,
                        enabled = labelState.isRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("⏹", fontSize = 13.sp)
                    }
                }
            }

            // 기록 출력 슬롯 (FlowRow: 각 칩이 쪼개지지 않고 화면을 벗어나면 다음 줄로 자동 줄바꿈)
            if (labelState.splitTimes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    labelState.splitTimes.forEachIndexed { index, timeStr ->
                        RecordChip(index = index + 1, timeStr = timeStr)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordChip(index: Int, timeStr: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = "$index: $timeStr",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// --- ⚙️ 설정 다이얼로그 ---
@Composable
fun SettingsDialog(
    settings: AutoSettings,
    onSettingsChanged: (AutoSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var isEnabled by remember { mutableStateOf(settings.isEnabled) }
    var minTime by remember { mutableFloatStateOf(settings.minTimeSec) }
    var maxTime by remember { mutableFloatStateOf(settings.maxTimeSec) }
    var isRandom by remember { mutableStateOf(settings.isRandom) }
    var nextPrepareInterval by remember { mutableFloatStateOf(settings.nextPrepareIntervalSec) }
    var stopMode by remember { mutableStateOf(settings.stopMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "출발신호 자동 반복 설정 ⚙️",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. 자동 반복 사용 여부
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("자동 반복 사용", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("구령 후 설정 시간에 출발 자동 신호", fontSize = 11.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { isEnabled = it }
                    )
                }

                HorizontalDivider()

                // 2. 최소/최대 시간 설정
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "준비-출발 간격 시간 설정",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )

                    Text("최소 시간: ${String.format(Locale.getDefault(), "%.1f초", minTime)}", fontSize = 12.sp)
                    Slider(
                        value = minTime,
                        onValueChange = {
                            minTime = it
                            if (maxTime < minTime) maxTime = minTime
                        },
                        valueRange = 1.0f..5.0f,
                        steps = 7
                    )

                    Text("최대 시간: ${String.format(Locale.getDefault(), "%.1f초", maxTime)}", fontSize = 12.sp)
                    Slider(
                        value = maxTime,
                        onValueChange = {
                            maxTime = it
                            if (minTime > maxTime) minTime = maxTime
                        },
                        valueRange = 1.0f..5.0f,
                        steps = 7
                    )
                }

                HorizontalDivider()

                // 3. 출발 신호 랜덤 여부
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("출발 신호 랜덤 시간", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("최소~최대 시간 사이 무작위 신호", fontSize = 11.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = isRandom,
                        onCheckedChange = { isRandom = it }
                    )
                }

                HorizontalDivider()

                // 4. 다음 준비까지의 시간 설정 (기본값 10초)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "다음 준비까지의 시간 설정",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text("대기 시간: ${String.format(Locale.getDefault(), "%.1f초", nextPrepareInterval)} (기본 10초)", fontSize = 12.sp)
                    Slider(
                        value = nextPrepareInterval,
                        onValueChange = { nextPrepareInterval = it },
                        valueRange = 1.0f..30.0f,
                        steps = 28
                    )
                }

                HorizontalDivider()

                // 5. 자동 반복 정지 조건
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("자동 반복 정지 조건", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = stopMode == AutoStopMode.MANUAL_CLICK,
                            onClick = { stopMode = AutoStopMode.MANUAL_CLICK }
                        )
                        Text("수동 조작 시 정지 (디폴트)", fontSize = 13.sp)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = stopMode == AutoStopMode.EXPLICIT_DISABLE,
                            onClick = { stopMode = AutoStopMode.EXPLICIT_DISABLE }
                        )
                        Text("설정에서 직접 해제할 때까지 계속", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSettingsChanged(
                        AutoSettings(
                            isEnabled = isEnabled,
                            minTimeSec = minTime,
                            maxTimeSec = maxTime,
                            isRandom = isRandom,
                            nextPrepareIntervalSec = nextPrepareInterval,
                            stopMode = stopMode
                        )
                    )
                    onDismiss()
                }
            ) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

// --- 레코드 DB 다이얼로그 ---
@Composable
fun RecordListDialog(
    repository: RecordRepository,
    onDismiss: () -> Unit
) {
    var records by remember { mutableStateOf(repository.getAllRecords()) }
    var editingRecordId by remember { mutableStateOf<String?>(null) }
    var editMemoText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "레코드 DB (저장된 출발 기록)",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (records.isEmpty()) {
                    Text(
                        text = "저장된 레코드가 없습니다.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                } else {
                    records.forEach { record ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = record.timestampFormatted,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(
                                            onClick = {
                                                editingRecordId = record.id
                                                editMemoText = record.memo
                                            }
                                        ) {
                                            Text("편집", fontSize = 12.sp)
                                        }

                                        TextButton(
                                            onClick = {
                                                repository.deleteRecord(record.id)
                                                records = repository.getAllRecords()
                                            }
                                        ) {
                                            Text("삭제", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }

                                if (editingRecordId == record.id) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = editMemoText,
                                            onValueChange = { editMemoText = it },
                                            label = { Text("메모 입력") },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Button(
                                            onClick = {
                                                repository.updateMemo(record.id, editMemoText)
                                                records = repository.getAllRecords()
                                                editingRecordId = null
                                            }
                                        ) {
                                            Text("저장", fontSize = 12.sp)
                                        }
                                    }
                                } else {
                                    if (record.memo.isNotEmpty()) {
                                        Text(
                                            text = "메모: ${record.memo}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                if (record.splitTimes.isNotEmpty()) {
                                    Text(
                                        text = "기록: ${record.splitTimes.joinToString(", ")}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                } else {
                                    Text(
                                        text = "기록된 슬롯 없음",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기")
            }
        }
    )
}

// --- 출발 규정 다이얼로그 ---
@Composable
fun RulesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "World Aquatics 출발 규정 안내",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RuleItem(
                    title = "1. 차려 명령 및 예비 동작(정지 상태) 규정",
                    subTitle = "World Aquatics Competition Regulations - Part 2, Chapter 3 (Swimming), Rule 4.1 & 4.2",
                    quote = "\"On the starter’s command 'take your marks', they shall immediately take up a starting position with at least one foot at the front of the starting platforms. The position of the hands is not relevant. When all swimmers are stationary, the Starter shall give the starting signal.\" (Rule 4.1)\n\n\"Any swimmer starting before the starting signal has been given, shall be disqualified.\" (Rule 4.2)",
                    description = "출발심판의 \"Take your marks\" 구령이 내려지면 선수는 지체 없이 블록 전면에 최소 한 발을 둔 준비 자세를 취해야 합니다. 규정상 예비 동작은 허용되지 않으며, 모든 영자가 완벽한 정지 상태(stationary)에 도달해야만 출발 신호가 주어집니다. 신호 출력 전 몸을 움직이거나 반동을 주는 행위는 부정출발로 실격 처리됩니다."
                )

                HorizontalDivider()

                RuleItem(
                    title = "2. 비프음 출력 간격 규정",
                    subTitle = "World Aquatics Competition Regulations - Part 2, Chapter 2 (Officials), Rule 2.4.2 & Rule 4.1",
                    quote = "\"The Starter shall have full control of the swimmers from the time the Referee turns the swimmers over to him/her until the race has commenced.\" (Rule 2.4.2)\n\n\"When all swimmers are stationary, the Starter shall give the starting signal.\" (Rule 4.1)",
                    description = "규정에는 구령 후 비프음(신호) 발생까지 몇 초를 기다려야 한다는 고정 시간 조항이 없습니다. 출발 통제권은 전적으로 출발심판에게 있으며, 출발심판이 8~10개 레인의 모든 선수가 흔들림 없이 완전히 멈춘 것을 육안으로 확인한 시점에 수동으로 신호를 작동시킵니다."
                )

                HorizontalDivider()

                RuleItem(
                    title = "3. 출발 신호 취소(Stand up) 규정",
                    subTitle = "World Aquatics Competition Regulations - Part 2, Chapter 2 (Officials), Rule 2.4.3",
                    quote = "\"If the Starter decides that a swimmer does not comply with the command, or for any other reason the start is not fair, the Starter may order the swimmers to stand up and the Referee will restart the race.\" (Rule 2.4.3)",
                    description = "선수가 구령 후에도 정지하지 않거나, 장내 소음 등으로 정상적이고 공정한 출발이 불가능하다고 판단될 경우 출발심판은 비프음을 울리지 않고 \"Stand up\"을 선언하여 선수를 일으켜 세운 뒤 절차를 재진행합니다."
                )

                HorizontalDivider()

                RuleItem(
                    title = "4. 최소 반응속도 및 계측 허용 오차 규정",
                    subTitle = "World Aquatics Competition Regulations - Part 2, Chapter 15 (Automatic Officiating Equipment), Rule 15.3.1 (구 SW 13.3)",
                    quote = "\"When Automatic Equipment is used, the results shall be official only to 1/100 of a second. (...) In relays, a swimmer shall be disqualified if their feet leave the starting platform before the preceding swimmer touches the wall, allowing for a tolerance of -0.03 seconds.\"",
                    description = "공식 계측 장비(스타팅 블록 패드 및 터치패드)는 1/100초 단위로 반응속도와 인계 시간을 판정합니다. 센서의 기계적 오차와 압력 해제 임계치를 감안하여 계측 시스템상 -0.03초(-30ms)까지는 규칙 준수로 간주하며, -0.04초 이하로 측정된 경우에 한해 부정출발(플라잉)로 자동 판정됩니다."
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("확인")
            }
        }
    )
}

@Composable
private fun RuleItem(
    title: String,
    subTitle: String,
    quote: String,
    description: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = subTitle,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Text(
                text = quote,
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp)
            )
        }
        Text(
            text = description,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 18.sp
        )
    }
}