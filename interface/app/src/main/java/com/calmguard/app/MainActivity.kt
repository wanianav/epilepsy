package com.calmguard.app

import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.sqrt

private val Ink = Color(0xFF193C34)
private val Green = Color(0xFF176B59)
private val Muted = Color(0xFF60746C)
private val Background = Color(0xFFF6F8F5)
private val Pale = Color(0xFFE8F1E9)
private val Line = Color(0xFFDAE4DB)
private val Amber = Color(0xFF895A13)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CalmGuardApp() }
    }
}

@Composable
fun CalmGuardApp(state: PrototypeState = viewModel()) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Green, onPrimary = Color.White,
        primaryContainer = Pale, onPrimaryContainer = Ink, background = Background,
        surface = Color.White, onSurface = Ink, onBackground = Ink, outline = Line,
        secondary = Muted, secondaryContainer = Pale, onSecondaryContainer = Green,
        surfaceVariant = Pale, onSurfaceVariant = Muted)) {
        val snack = remember { SnackbarHostState() }
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_STOP) state.pause() }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) {
                runCatching { requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(state.exportCsv().toByteArray(Charsets.UTF_8)) } }
                    .onSuccess { state.notice = "Event history exported." }
                    .onFailure { state.notice = "Could not export. Please choose another location." }
            }
        }
        LaunchedEffect(state.notice) { state.notice?.let { snack.showSnackbar(it); state.notice = null } }
        BackHandler(state.research) { state.research = false }
        Scaffold(
            containerColor = Background,
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                NavigationBar(containerColor = Background, tonalElevation = 0.dp) {
                    val labels = listOf("Overview", "Safe Mode", "Samples", "History")
                    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.Shield, Icons.AutoMirrored.Outlined.ShowChart, Icons.Outlined.History)
                    labels.forEachIndexed { i, label -> NavigationBarItem(
                        selected = state.page == i && !state.research,
                        onClick = { state.page = i; state.research = false },
                        icon = { Icon(icons[i], contentDescription = null) }, label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Pale, selectedIconColor = Green,
                            selectedTextColor = Green, unselectedIconColor = Muted, unselectedTextColor = Muted)) }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().weight(1f)) {
                    AppHeader(state)
                    key(state.page, state.research) {
                        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            if (state.research) ResearchScreen(state) else when(state.page) {
                                0 -> OverviewScreen(state)
                                1 -> SafeScreen(state)
                                2 -> SamplesScreen(state)
                                else -> HistoryScreen(state) { export.launch("calmguard-events.csv") }
                            }
                        }
                    }
                }
            }
        }
        if (state.alert != AlertStage.IDLE) TestAlertDialog(state)
    }
}

@Composable
private fun AppHeader(state: PrototypeState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(37.dp).clip(RoundedCornerShape(12.dp)).background(Green), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.HealthAndSafety, null, tint = Color.White, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.width(9.dp))
        Text("calmguard", fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.8).sp, color = Ink)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { state.research = !state.research }) {
            Icon(if(state.research) Icons.Outlined.Close else Icons.Outlined.Info, contentDescription = if(state.research) "Close research details" else "Research details", tint = Green)
        }
    }
}

@Composable
private fun Label(text: String, color: Color = Green) {
    Text(text.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, color = color)
}

@Composable
private fun Title(text: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(text, fontSize = 31.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, color = Ink)
        Text(subtitle, fontSize = 14.sp, lineHeight = 21.sp, color = Muted)
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, tint: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(tint).border(1.dp, Line, RoundedCornerShape(22.dp)).padding(19.dp), verticalArrangement = Arrangement.spacedBy(13.dp), content = content)
}

@Composable
private fun Pill(text: String, tint: Color = Pale, ink: Color = Green) {
    Text(text, modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(tint).padding(horizontal = 9.dp, vertical = 5.dp), color = ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
}

@Composable
private fun SectionTitle(title: String, action: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
        if(action != null) TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) { Text(action, fontSize = 12.sp) }
    }
}

@Composable
private fun OverviewScreen(state: PrototypeState) {
    Label("Your research companion")
    Title("A calmer\ndigital day.", "Explore a gentler interface and movement alerts, at your own pace.")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(25.dp)).background(Brush.linearGradient(listOf(Color(0xFF1B6252), Color(0xFF254D43)))).padding(22.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Shield, null, tint = Color(0xFFC4E7B3), modifier = Modifier.size(29.dp))
            Spacer(Modifier.weight(1f))
            Pill(if(state.safe.enabled) "CONTROLS ACTIVE" else "NORMAL DEMO", Color(0xFF386C5C), Color.White)
        }
        Text(if(state.safe.enabled) "Safe Mode is on" else "Choose a calmer view", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Text("Manual media. Reduced motion.\nFewer unexpected changes.", color = Color(0xFFD5E6DB), fontSize = 14.sp, lineHeight = 22.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.page = 1 }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White), contentPadding = PaddingValues(0.dp)) {
                Text("Manage your controls"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.weight(1f))
            Switch(checked = state.safe.enabled, onCheckedChange = { state.updateSafe(state.safe.copy(enabled = it)) }, modifier = Modifier.semantics { contentDescription = "Overview Safe Mode" },
                colors = SwitchDefaults.colors(checkedThumbColor = Green, checkedTrackColor = Color(0xFFD5E9BD)))
        }
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(43.dp).clip(CircleShape).background(Pale), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Outlined.ShowChart, null, tint = Green) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text("Movement explorer", fontWeight = FontWeight.SemiBold, fontSize = 17.sp); Text("Recorded samples · works offline", color = Muted, fontSize = 12.sp) }
        }
        WaveChart(state.clips.first().points.take(80), Modifier.fillMaxWidth().height(74.dp), showLabels = false)
        Button(onClick = { state.page = 2 }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Explore sample data")
        }
    }
    SectionTitle("Try the alert journey")
    Panel(tint = Color(0xFFF3EEDD)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.NotificationsNone, null, tint = Amber)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("A little time to respond", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("Try a 20-second countdown and cancel it at any time. This test stays on this phone.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
        OutlinedButton(onClick = { state.startAlert() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Start test alert") }
    }
    PrototypeNote()
}

@Composable
private fun PrototypeNote() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Science, null, tint = Muted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text("Research prototype using sample data. No wristband is connected. This app does not provide medical monitoring.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun SafeScreen(state: PrototypeState) {
    Label("A more comfortable screen")
    Title("Your view.\nYour controls.", "Choose your in-app preferences and optional display controls for other apps.")
    Panel(tint = Pale) {
        SettingRow(Icons.Outlined.Shield, "Safe Mode", "Apply your content preferences", state.safe.enabled) { state.updateSafe(state.safe.copy(enabled = it)) }
    }
    DeviceDisplayControls(state)
    SectionTitle("Inside CalmGuard")
    Panel {
        SettingRow(Icons.Outlined.PlayCircleOutline, "Tap to play", "Hold automatic media requests", state.safe.manualMedia, state.safe.enabled) { state.updateSafe(state.safe.copy(manualMedia = it)) }
        HorizontalDivider(color = Line)
        SettingRow(Icons.Outlined.SlowMotionVideo, "Reduced motion", "Prefer still, predictable transitions", state.safe.reducedMotion, state.safe.enabled) { state.updateSafe(state.safe.copy(reducedMotion = it)) }
        HorizontalDivider(color = Line)
        SettingRow(Icons.Outlined.PauseCircleOutline, "Hold feed updates", "Keep unrequested changes on hold", state.safe.holdUpdates, state.safe.enabled) { state.updateSafe(state.safe.copy(holdUpdates = it)) }
    }
    SectionTitle("A safe place to try")
    Panel {
        Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFFE5ECE1)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Landscape, null, tint = Green, modifier = Modifier.size(34.dp))
                Text("Still media preview", color = Green, fontSize = 12.sp)
            }
        }
        Text("These buttons request scripted events. See how your settings handle them in History.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        OutlinedButton(onClick = { state.tryEvent("Autoplay") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { Text("Try an autoplay request") }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedButton(onClick = { state.tryEvent("Motion") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("Try motion", fontSize = 12.sp) }
            OutlinedButton(onClick = { state.tryEvent("Feed update") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("Try feed update", fontSize = 12.sp) }
        }
    }
    Text("Media and motion demo controls apply within CalmGuard. Optional device dimming works over supported apps, without inspecting their content.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
}

@Composable
private fun DeviceDisplayControls(state: PrototypeState) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = state.deviceDisplay
    val status by DeviceDisplayService.status.collectAsStateWithLifecycle()
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_RESUME) revision++ }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        lifecycle.addObserver(observer); controller.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { lifecycle.removeObserver(observer); controller.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val options = remember(revision) { controller.options() }
    val overlayAllowed = remember(revision) { controller.overlayAllowed() }
    val notificationsAllowed = remember(revision) { controller.notificationsAllowed() }
    val brightnessAllowed = remember(revision) { controller.brightnessAllowed() }
    var dim by remember(options.dimPercent) { mutableFloatStateOf(options.dimPercent.toFloat()) }
    var brightness by remember(options.brightnessPercent) { mutableFloatStateOf(options.brightnessPercent.toFloat()) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        revision++
        if(!allowed) state.notice = "Display mode needs a visible notification with a Stop button. You can enable it in notification settings."
    }
    fun openSettings(intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure { state.notice = "This settings screen is unavailable. Open Android settings manually." }
    }
    fun save(next: DisplayOptions) {
        if(status.running && next.useBrightness && !brightnessAllowed) {
            state.notice = "Allow brightness settings access first."
        } else { controller.save(next); revision++ }
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PhoneAndroid, null, tint = Green)
            Spacer(Modifier.width(9.dp))
            Text("Device display controls", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Pill(if(status.running) "DIMMING ACTIVE" else "OPTIONAL · CURRENTLY OFF")
        Text("A touch-through dimming layer can stay over supported apps after you leave CalmGuard. It does not read or record the screen.", fontSize = 13.sp, color = Muted, lineHeight = 20.sp)
        Text("Dimming does not remove flashing or establish seizure safety. Some apps and system screens hide overlays.", fontSize = 12.sp, color = Amber, lineHeight = 18.sp)
        HorizontalDivider(color = Line)
        Text("Dimming strength · ${dim.toInt()}%", fontWeight = FontWeight.Medium, fontSize = 13.sp)
        Slider(value = dim, onValueChange = { dim = it }, valueRange = 5f..40f, steps = 6,
            onValueChangeFinished = { save(options.copy(dimPercent = dim.toInt())) },
            modifier = Modifier.semantics { contentDescription = "Dimming strength" })
        Text("Overlay access: ${if(overlayAllowed) "allowed" else "needed"}", color = Muted, fontSize = 12.sp)
        if(!overlayAllowed) OutlinedButton(onClick = {
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { Text("Allow display over other apps") }
        Text("Stop notification: ${if(notificationsAllowed) "allowed" else "needed"}", color = Muted, fontSize = 12.sp)
        if(!notificationsAllowed) {
            if(Build.VERSION.SDK_INT >= 33) OutlinedButton(onClick = { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { Text("Allow display notification") }
            TextButton(onClick = { openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Open notification settings") }
        }
        HorizontalDivider(color = Line)
        SettingRow(Icons.Outlined.BrightnessMedium, "Phone brightness preset", "Optional; temporarily replaces adaptive brightness", options.useBrightness) { save(options.copy(useBrightness = it)) }
        if(options.useBrightness) {
            Text("Preset brightness · ${brightness.toInt()}%", color = Muted, fontSize = 12.sp)
            Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 15f..75f, steps = 11,
                onValueChangeFinished = { save(options.copy(brightnessPercent = brightness.toInt())) },
                modifier = Modifier.semantics { contentDescription = "Phone brightness preset" })
            if(!brightnessAllowed) OutlinedButton(onClick = {
                openSettings(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { Text("Allow brightness settings access") }
        }
        Text("When the mode stops, the previous brightness is restored if you have not changed it yourself. Apps can override the system brightness.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        if(!state.safe.enabled) Text("Turn on Safe Mode above to start device display controls.", color = Muted, fontSize = 12.sp)
        if(status.running) Button(onClick = {
            state.notice = controller.disable(); state.log("Device display stopped", "The dimming layer was removed. Brightness restoration was requested.", "safe"); revision++
        }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp)) { Text("Stop device display mode") }
        else Button(onClick = {
            val error = controller.enable()
            if(error != null) state.notice = error
            else state.log("Device display requested", "Requested a user-enabled dimming layer over supported apps. This does not filter flashing content.", "safe")
            revision++
        }, enabled = state.safe.enabled && overlayAllowed && notificationsAllowed && (!options.useBrightness || brightnessAllowed),
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp)) { Text("Start device display mode") }
        status.error?.let { Text(it, color = Amber, fontSize = 12.sp, lineHeight = 18.sp) }
        if(controller.brightness.hasSnapshot && !status.running) TextButton(onClick = {
            state.notice = runCatching { controller.brightness.restore() }.getOrElse { "Review brightness in Android settings." }; revision++
        }) { Text("Restore previous brightness") }
        Text("Linked to Safe Mode after you start it. Stops when the screen locks; restart it here when needed. You can also stop it from the persistent notification.", fontSize = 11.sp, color = Muted, lineHeight = 17.sp)
    }
    Panel(tint = Pale) {
        Text("Android motion and display settings", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text("In Accessibility, look for Remove animations, Extra dim or colour correction. Availability varies by phone. Each app controls its own autoplay and custom animations.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        OutlinedButton(onClick = { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { Text("Open accessibility settings") }
        TextButton(onClick = { openSettings(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }) { Text("Open Android display settings") }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if(enabled) Green else Muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Switch(checked, onChecked, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun SamplesScreen(state: PrototypeState) {
    Label("Movement explorer")
    Title("Understand\nthe pattern.", "Replay recorded movements and explore an on-device model. No hardware needed.")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.clips.forEachIndexed { index, clip -> FilterChip(selected = state.selected == index,
            onClick = { state.choose(index) }, label = { Text(clip.title, fontSize = 12.sp) }) }
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("RECORDED SAMPLE")
            Spacer(Modifier.weight(1f))
            Text("${state.windowIndex + 1} / ${state.clip.windows.size}", color = Muted, fontSize = 12.sp)
        }
        Text(state.clip.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(state.clip.description, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        WaveChart(state.chartPoints, Modifier.fillMaxWidth().height(148.dp))
        LinearProgressIndicator(progress = { (state.windowIndex + 1f) / state.clip.windows.size }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = Green, trackColor = Pale)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { state.togglePlayback() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(11.dp)) {
                Icon(if(state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(5.dp)); Text(if(state.playing) "Pause replay" else "Play sample")
            }
            OutlinedIconButton(onClick = { state.reset() }) { Icon(Icons.Outlined.Replay, contentDescription = "Reset sample") }
        }
        Text("${state.clip.source} · participant ${state.clip.participant}\nSource label: ${state.clip.label}", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
    }
    Panel(tint = Pale) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Psychology, null, tint = Green)
            Spacer(Modifier.width(9.dp)); Text("Random Forest", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Pill("ON DEVICE", Color.White)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(state.score?.let { "${(it * 100).toInt()}" } ?: "—", fontSize = 43.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.Medium)
            if(state.score != null) Text(" / 100", color = Muted, modifier = Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f))
            Text("Pattern score", color = Green, fontSize = 12.sp, modifier = Modifier.padding(bottom = 9.dp))
        }
        Text(when { state.score == null -> "Analyse a window or start playback."
            state.score!! >= state.model.threshold -> "Above the simulated-movement threshold."
            else -> "Below the simulated-movement threshold." }, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        Text("A model score is not the probability of a real seizure. The model learned from volunteer simulations.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedButton(onClick = { state.analyseCurrent() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { Text("Analyse this window") }
    }
    Panel {
        SettingRow(Icons.Outlined.NotificationsNone, "Replay test alerts", "Trigger after two above-threshold windows", state.autoAlert) { state.autoAlert = it }
        Text("Alerts are local demonstrations. Playback timing is illustrative; sample timestamps are irregular.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
    }
    TextButton(onClick = { state.research = true }) { Text("See datasets and model evaluation"); Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, modifier = Modifier.size(16.dp)) }
}

@Composable
private fun WaveChart(points: List<MotionPoint>, modifier: Modifier, showLabels: Boolean = true) {
    val values = points.map { sqrt(it.x * it.x + it.y * it.y + it.z * it.z).toFloat() }
    Column(modifier.semantics { contentDescription = "Recorded acceleration magnitude waveform" }) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            if(values.size < 2) return@Canvas
            val low = values.min(); val range = (values.max() - low).coerceAtLeast(0.15f)
            repeat(4) { row -> val y = size.height * row / 3f; drawLine(Line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f) }
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = size.width * i / (values.size - 1)
                val y = size.height * (0.90f - 0.8f * (v - low) / range)
                if(i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Green, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
        }
        if(showLabels) Row(Modifier.fillMaxWidth().padding(top = 7.dp)) {
            Text("Recorded movement strength", fontSize = 9.sp, color = Muted)
            Spacer(Modifier.weight(1f)); Text("40 readings", fontSize = 9.sp, color = Muted)
        }
    }
}

@Composable
private fun HistoryScreen(state: PrototypeState, export: () -> Unit) {
    var filter by remember { mutableStateOf("All") }
    var confirmClear by remember { mutableStateOf(false) }
    Label("Your local research log")
    Title("Every action,\na little clearer.", "Review content controls, sample analysis and test alerts. History stays on this device.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        listOf("All", "Visual", "Movement", "Alerts").forEach { value -> FilterChip(filter == value, { filter = value }, label = { Text(value) }) }
    }
    val visible = state.events.filter { when(filter) { "Visual" -> it.kind == "safe"; "Movement" -> it.kind == "sample"; "Alerts" -> it.kind == "alert"; else -> true } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${visible.size} ${if(visible.size == 1) "event" else "events"}", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = export, enabled = state.events.isNotEmpty()) { Icon(Icons.Outlined.FileDownload, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Export CSV", fontSize = 12.sp) }
    }
    if(visible.isEmpty()) Panel {
        Icon(Icons.Outlined.History, null, tint = Green, modifier = Modifier.size(32.dp))
        Text("A fresh start", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Try a Safe Mode control, analyse a sample or start a test alert. Your events will appear here.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
    }
    visible.forEach { entry -> Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(when(entry.kind) { "safe" -> Icons.Outlined.Shield; "alert" -> Icons.Outlined.NotificationsNone; else -> Icons.AutoMirrored.Outlined.ShowChart }, null, tint = Green, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp)); Text(entry.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        }
        Text(entry.detail, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text(displayTime(entry.time), color = Muted, fontSize = 10.sp)
    } }
    if(state.events.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear local history") }
    if(confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear event history?") }, text = { Text("This removes the events stored on this phone. Export them first if you need them for your research.") },
        confirmButton = { TextButton(onClick = { state.clearHistory(); confirmClear = false }) { Text("Clear history") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep history") } })
}

@Composable
private fun ResearchScreen(state: PrototypeState) {
    val report = state.model.report
    var name by remember { mutableStateOf(state.caregiver) }
    Label("The research behind the demo")
    Title("Know the data.\nKnow the limits.", "This prototype explores accessibility controls and classification of recorded movement.")
    Panel {
        Pill("UAL · VOLUNTEER SIMULATIONS")
        Text("Movement classification", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text("64 trees · 12 features · 40 readings per window", fontSize = 12.sp, color = Muted)
        Text("Trained on participants 0, 1 and 2. Participant 3 is held out for the evaluation below and the UAL sample clips.", fontSize = 13.sp, lineHeight = 20.sp, color = Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ResearchMetric("Sensitivity", report.getDouble("sensitivity"))
            ResearchMetric("Specificity", report.getDouble("specificity"))
            ResearchMetric("F1", report.getDouble("f1"))
        }
        Text("${report.getInt("test_windows")} held-out windows · threshold ${state.model.threshold}. These are simulated-movement results, not clinical seizure detection results.", fontSize = 11.sp, lineHeight = 17.sp, color = Muted)
    }
    Panel {
        Text("Across volunteers", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        val folds = report.getJSONArray("leave_one_volunteer_out")
        for(i in 0 until folds.length()) {
            val fold = folds.getJSONObject(i)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Held-out volunteer ${fold.getInt("held_out_user")}", color = Muted, fontSize = 12.sp)
                Text("${"%.1f".format(Locale.US, fold.getDouble("sensitivity") * 100)}% sensitivity", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        Text("Performance varies substantially between people. One held-out volunteer has low sensitivity; this model is suitable for an exploratory demo only.", color = Amber, fontSize = 12.sp, lineHeight = 18.sp)
        HorizontalDivider(color = Line)
        val challenge = report.getJSONObject("wisdm_challenge")
        Text("WISDM everyday-movement challenge", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("${"%.1f".format(Locale.US, challenge.getDouble("positive_window_rate") * 100)}% of ${challenge.getInt("windows")} ordinary-activity windows exceeded the demo threshold. This is a window rate, not false alarms per day.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
    Panel {
        Text("The sample library", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("UAL Smart Home: recordings from four volunteers simulating seizure-like movements. Labels describe simulations. CC BY 4.0.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        HorizontalDivider(color = Line)
        Text("WISDM: smartwatch recordings of daily activities. Used as a separate false-alarm challenge, never as clinical seizure examples. CC BY 4.0.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        Text("UAL units are assumed to be g based on the signal scale; this is not confirmed in the publisher's description. WISDM acceleration is converted from m/s² to g. Replay uses nominal sample ordering, because UAL timestamps are bursty.", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
        Text("Full source links, file checksums and evaluation details are included with the project.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
    }
    Panel {
        Text("Personalise the test", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = name, onValueChange = { name = it.take(50) }, label = { Text("Demo caregiver name") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp))
        Text("A display name for the local alert preview. No phone number or location is collected.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        Button(onClick = { state.saveCaregiver(name); state.notice = "Demo caregiver saved." }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp)) { Text("Save demo caregiver") }
    }
    PrototypeNote()
}

@Composable
private fun ResearchMetric(label: String, value: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("${(value * 100).toInt()}%", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = Green)
        Text(label, fontSize = 11.sp, color = Muted)
    }
}

@Composable
private fun TestAlertDialog(state: PrototypeState) {
    val complete = state.alert == AlertStage.COMPLETE
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(if(complete) Icons.Outlined.TaskAlt else Icons.Outlined.NotificationsNone, null, tint = Green) },
        title = { Text(if(complete) "Test complete" else "A moment to respond", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Pill("LOCAL DEMONSTRATION")
                if(!complete) {
                    Text("${state.remaining}", fontSize = 62.sp, fontWeight = FontWeight.Light, color = Green, modifier = Modifier.semantics { contentDescription = "${state.remaining} seconds remaining" })
                    Text("Cancel this test before the countdown ends, or let it finish to see the alert preview.", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                } else {
                    Text("Preview for ${state.caregiver}", fontWeight = FontWeight.SemiBold)
                    Text("A test movement alert was generated from sample data. Please review the demonstration.", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                }
                Text("No messages are sent. No location is shared.", color = Green, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { if(complete) state.dismissAlert() else state.cancelAlert() }) { Text(if(complete) "Done" else "Cancel test alert", fontWeight = FontWeight.Bold) } },
        dismissButton = { if(!complete) TextButton(onClick = { state.finishAlert() }) { Text("Preview result now") } },
        containerColor = Background, shape = RoundedCornerShape(26.dp)
    )
}
