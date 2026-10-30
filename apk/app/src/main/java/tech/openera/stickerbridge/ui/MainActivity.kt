package tech.openera.stickerbridge.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tech.openera.stickerbridge.Graph
import tech.openera.stickerbridge.R
import tech.openera.stickerbridge.data.Pack
import tech.openera.stickerbridge.data.SyncStatus
import tech.openera.stickerbridge.ui.theme.AppTheme
import tech.openera.stickerbridge.update.UpdateState
import tech.openera.stickerbridge.whatsapp.WaApp
import tech.openera.stickerbridge.whatsapp.WhatsApp

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var startTab by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        startTab = intent.getStringExtra(EXTRA_TAB)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val session by vm.session.collectAsStateWithLifecycle()
            val s = settings
            val ses = session
            if (s == null || ses == null) return@setContent // first frame while settings load
            AppTheme(s.theme, s.wallpaperColors) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root(vm, ses.connected, startTab)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startTab = intent.getStringExtra(EXTRA_TAB)
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}

private enum class Route { Welcome, Connect, Home }

@Composable
private fun Root(vm: AppViewModel, connected: Boolean, startTab: String?) {
    var route by rememberSaveable { mutableStateOf(if (connected) Route.Home else Route.Welcome) }
    val link by vm.link.collectAsStateWithLifecycle()
    // Signed out elsewhere (Settings, or the server ended the link): back to the start.
    LaunchedEffect(connected) { if (!connected && route == Route.Home) route = Route.Welcome }

    AnimatedContent(route, label = "route", transitionSpec = { fadeIn() togetherWith fadeOut() }) { r ->
        when (r) {
            Route.Welcome -> WelcomeScreen { route = Route.Connect }
            Route.Connect -> {
                BackHandler { route = Route.Welcome }
                val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                ConnectScreen(
                    link = link,
                    onBack = { route = Route.Welcome },
                    onStart = vm::startLink,
                    onOpened = vm::markWaiting,
                    onContinue = {
                        vm.finishLink()
                        if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                        route = Route.Home
                    },
                )
            }
            Route.Home -> Home(vm, startTab)
        }
    }
}

@Composable
private fun Home(vm: AppViewModel, startTab: String?) {
    val ctx = LocalContext.current
    val g = remember { Graph.get(ctx) }
    var tab by rememberSaveable { mutableStateOf(startTab ?: "stickers") }
    LaunchedEffect(startTab) { if (startTab != null) tab = startTab }

    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val store by vm.stickers.collectAsStateWithLifecycle()
    val packs by vm.packs.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val wa by vm.waStatus.collectAsStateWithLifecycle()
    val installed by vm.installedWa.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshWhatsApp()
        vm.syncNow()
    }
    // Quiet checks every 15 s, only while the app is on screen.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.watchWhileOpen() }
    }
    LaunchedEffect(packs) { vm.refreshWhatsApp() }

    // Report sync errors once.
    LaunchedEffect(sync) {
        val r = (sync as? SyncStatus.Done)?.result ?: return@LaunchedEffect
        if (r.error != null && r.error != "Not connected") snackbar.showSnackbar(r.error)
        else if (r.added > 0 && tab != "stickers") snackbar.showSnackbar(if (r.added == 1) "1 new sticker" else "${r.added} new stickers")
    }

    val addLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        vm.refreshWhatsApp()
        if (res.resultCode == Activity.RESULT_CANCELED) {
            val err = res.data?.getStringExtra("validation_error")
            if (err != null) scope.launch { snackbar.showSnackbar("WhatsApp couldn't add the pack: $err") }
        }
    }
    val onAdd: (Pack, WaApp) -> Unit = { p, app ->
        try {
            addLauncher.launch(WhatsApp.addIntent(ctx, app, p.id, p.name))
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("${app.label} isn't installed or is too old.") }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        (update as? UpdateState.NeedsPermission)?.let { vm.installUpdate(it.release) }
    }

    val banner: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            notice?.let { InfoPanel(icon = R.drawable.ic_info, tone = Tone.Accent) { Text(it) } }
            UpdateBanner(
                update,
                onInstall = vm::installUpdate,
                onPermission = { permissionLauncher.launch(vm.updatePermissionIntent()) },
                onDismiss = vm::dismissUpdate,
            )
        }
    }

    val s = settings ?: return
    val ses = session ?: return

    HomeFrame(tab, { tab = it; io.sentry.Sentry.addBreadcrumb("Opened $it tab", "navigation") }, snackbar) {
            when (tab) {
                "packs" -> PacksTab(packs, g.store.dir, installed, wa, banner, onAdd)
                "settings" -> SettingsTab(
                    s, ses, banner,
                    onChange = vm::updateSettings,
                    onSyncNow = vm::syncNow,
                    onCheckUpdate = {
                        vm.checkUpdate()
                    },
                    onDisconnect = { vm.signOut(deleteData = false) },
                    onDeleteAll = { vm.signOut(deleteData = true) },
                )
                else -> StickersTab(
                    store.stickers, packs, g.store.dir, sync, ses.lastSyncAt, banner,
                    onSync = vm::syncNow,
                    onRemove = { vm.remove(it) },
                    onEmojis = { id, e -> vm.setEmojis(id, e) },
                    onSeen = { vm.markSeen() },
                )
            }
    }

    // "Check for updates" with nothing new: say so.
    LaunchedEffect(update) {
        if (update is UpdateState.UpToDate && tab == "settings") snackbar.showSnackbar("You're on the latest version.")
        if (update is UpdateState.Failed && tab == "settings") snackbar.showSnackbar((update as UpdateState.Failed).message)
    }
}

/** The tab bar and snackbar around the three tabs. */
@Composable
fun HomeFrame(tab: String, onTab: (String) -> Unit, snackbar: SnackbarHostState, content: @Composable () -> Unit) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
                val colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOf(
                    Triple("stickers", "Stickers", R.drawable.ic_sticker),
                    Triple("packs", "Packs", R.drawable.ic_packs),
                    Triple("settings", "Settings", R.drawable.ic_settings),
                ).forEach { (key, label, icon) ->
                    NavigationBarItem(
                        selected = tab == key,
                        onClick = { onTab(key) },
                        icon = { Ico(icon, null, tint = androidx.compose.material3.LocalContentColor.current, size = 22.dp) },
                        label = { Text(label) },
                        colors = colors,
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) { content() }
    }
}

@Composable
fun UpdateBanner(state: UpdateState, onInstall: (tech.openera.stickerbridge.data.AppRelease) -> Unit, onPermission: () -> Unit, onDismiss: () -> Unit) {
    when (state) {
        is UpdateState.Available -> Group {
            Column(Modifier.padding(16.dp)) {
                Text("Update available · ${state.release.versionName}", style = MaterialTheme.typography.titleMedium)
                if (state.release.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(state.release.notes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Update", { onInstall(state.release) }, Modifier.weight(1f), leading = R.drawable.ic_download)
                    if (!state.release.mandatory) TextButton(onClick = onDismiss, modifier = Modifier.height(52.dp)) { Text("Later") }
                }
            }
        }
        is UpdateState.Downloading -> Group {
            Column(Modifier.padding(16.dp)) {
                Text("Downloading ${state.release.versionName}…", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
        is UpdateState.Installing -> InfoPanel(icon = R.drawable.ic_download, tone = Tone.Accent) {
            Text("Confirm the install when Android asks. Your stickers are kept.")
        }
        is UpdateState.NeedsPermission -> Group {
            Column(Modifier.padding(16.dp)) {
                Text("One-time permission", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Android needs you to allow Sticker Bridge to install updates. Turn on \"Allow from this source\", then come back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Open settings", onPermission)
            }
        }
        is UpdateState.Failed -> if (state.release != null) InfoPanel(icon = R.drawable.ic_info, tone = Tone.Error) {
            Row {
                Text(state.message, Modifier.weight(1f))
                TextButton(onClick = { onInstall(state.release) }) { Text("Retry") }
            }
        }
        else -> Unit
    }
}
