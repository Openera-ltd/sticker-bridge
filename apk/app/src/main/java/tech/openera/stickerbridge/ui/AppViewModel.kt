package tech.openera.stickerbridge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tech.openera.stickerbridge.Graph
import tech.openera.stickerbridge.data.AppRelease
import tech.openera.stickerbridge.data.AppSettings
import tech.openera.stickerbridge.data.LinkStart
import tech.openera.stickerbridge.data.Pack
import tech.openera.stickerbridge.data.Session
import tech.openera.stickerbridge.data.SyncStatus
import tech.openera.stickerbridge.sync.SyncScheduler
import tech.openera.stickerbridge.whatsapp.WaApp
import tech.openera.stickerbridge.whatsapp.WhatsApp

sealed interface LinkUi {
    data object Idle : LinkUi
    data object Loading : LinkUi
    data class Code(val start: LinkStart, val waiting: Boolean) : LinkUi
    data class Linked(val username: String) : LinkUi
    data object Expired : LinkUi
    data class Error(val message: String) : LinkUi
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val g = Graph.get(app)

    val settings: StateFlow<AppSettings?> = g.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val session: StateFlow<Session?> = g.settings.session.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val stickers = g.store.state
    val sync: StateFlow<SyncStatus> = g.sync.status
    val update = g.updates.state

    val packs: StateFlow<List<Pack>> = g.store.state.map { g.store.packs() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _wa = MutableStateFlow<Map<String, Map<WaApp, Boolean?>>>(emptyMap())
    /** For each ready pack: which WhatsApp apps already have it. */
    val waStatus: StateFlow<Map<String, Map<WaApp, Boolean?>>> = _wa.asStateFlow()

    private val _installed = MutableStateFlow<List<WaApp>>(emptyList())
    val installedWa: StateFlow<List<WaApp>> = _installed.asStateFlow()

    private val _donation = MutableStateFlow<tech.openera.stickerbridge.data.DonationInfo?>(null)
    /** Donation details, only when they carry the owner's valid signature. */
    val donation: StateFlow<tech.openera.stickerbridge.data.DonationInfo?> = _donation.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _link = MutableStateFlow<LinkUi>(LinkUi.Idle)
    val link: StateFlow<LinkUi> = _link.asStateFlow()
    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { g.api.config() }.getOrNull()?.let { _notice.value = it.notice }
        }
        viewModelScope.launch {
            val signed = runCatching { g.api.donation() }.getOrNull()
            _donation.value = signed?.takeIf { tech.openera.stickerbridge.data.Integrity.genuine(getApplication()) }?.let { tech.openera.stickerbridge.data.Donations.verify(it, tech.openera.stickerbridge.BuildConfig.DONATION_KEY) }
        }
        viewModelScope.launch {
            val s = g.settings.current()
            if (s.autoUpdateCheck) g.updates.check()
        }
    }

    // ---------- connecting ----------

    fun startLink() {
        pollJob?.cancel()
        _link.value = LinkUi.Loading
        pollJob = viewModelScope.launch {
            val start = try {
                g.api.startLink(g.settings.installId())
            } catch (e: Exception) {
                _link.value = LinkUi.Error("Couldn't reach Sticker Bridge. Check your connection and try again.")
                return@launch
            }
            _link.value = LinkUi.Code(start, waiting = false)
            while (isActive) {
                if (System.currentTimeMillis() > start.expiresAt) {
                    _link.value = LinkUi.Expired; return@launch
                }
                val st = runCatching { g.api.linkStatus(start.code, start.pollToken) }.getOrNull()
                when (st?.status) {
                    "linked" -> {
                        val token = st.token
                        val account = st.account
                        if (token != null && account != null) {
                            g.settings.saveLink(token, account)
                            _link.value = LinkUi.Linked(account.username)
                            return@launch
                        }
                    }
                    "expired" -> {
                        _link.value = LinkUi.Expired; return@launch
                    }
                }
                delay(2_000)
            }
        }
    }

    fun markWaiting() {
        (_link.value as? LinkUi.Code)?.let { _link.value = it.copy(waiting = true) }
    }

    /** Called once the user taps Continue after connecting. */
    fun finishLink() {
        pollJob?.cancel()
        viewModelScope.launch {
            SyncScheduler.apply(getApplication(), g.settings.current(), true)
            syncNowInternal()
        }
    }

    // ---------- syncing ----------

    fun syncNow() {
        viewModelScope.launch { syncNowInternal() }
    }

    /** Starts the silent 15-second background check (the app is in front, so Android allows it). */
    fun ensureBackgroundSync() {
        viewModelScope.launch {
            SyncScheduler.apply(getApplication(), g.settings.current(), g.settings.currentSession().connected)
        }
    }

    private suspend fun syncNowInternal(quiet: Boolean = false) {
        val r = g.sync.sync(quiet)
        if (r.added > 0) WhatsApp.notifyChanged(getApplication())
        refreshWhatsApp()
    }

    fun markSeen() = viewModelScope.launch { g.sync.markAllSeen() }
    fun remove(id: String) = viewModelScope.launch { g.sync.remove(id); refreshWhatsApp() }

    // ---------- packs ----------

    fun createPack(name: String, stickerIds: List<String>, done: (Int) -> Unit = {}) = viewModelScope.launch {
        val made = g.sync.createPack(name, stickerIds)
        WhatsApp.notifyChanged(getApplication())
        refreshWhatsApp()
        done(made.size)
    }

    fun addToPack(packId: String, stickerIds: List<String>, done: (Int) -> Unit = {}) = viewModelScope.launch {
        val n = g.sync.addToPack(packId, stickerIds)
        WhatsApp.notifyChanged(getApplication())
        refreshWhatsApp()
        done(n)
    }

    fun removeFromPack(packId: String, stickerId: String) = viewModelScope.launch {
        g.sync.removeFromPack(packId, stickerId); WhatsApp.notifyChanged(getApplication()); refreshWhatsApp()
    }

    fun renamePack(packId: String, name: String) = viewModelScope.launch {
        g.sync.renamePack(packId, name); WhatsApp.notifyChanged(getApplication())
    }

    fun deletePack(packId: String) = viewModelScope.launch {
        g.sync.deletePack(packId); WhatsApp.notifyChanged(getApplication()); refreshWhatsApp()
    }

    // ---------- WhatsApp ----------

    fun refreshWhatsApp() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val installed = WhatsApp.installed(ctx)
            _installed.value = installed
            _wa.value = packs.value.filter { it.ready }.associate { p ->
                p.id to installed.associateWith { app -> WhatsApp.isAdded(ctx, app, p.id) }
            }
        }
    }

    // ---------- settings ----------

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val before = g.settings.current()
            g.settings.update(block)
            val after = g.settings.current()
            SyncScheduler.apply(getApplication(), after, g.settings.currentSession().connected)
            if (before.outline != after.outline) g.sync.reprocessAll()
            if (before.outline != after.outline) {
                WhatsApp.notifyChanged(getApplication())
            }
        }
    }

    fun signOut(deleteData: Boolean) {
        viewModelScope.launch {
            g.sync.signOut(deleteData)
            SyncScheduler.apply(getApplication(), g.settings.current(), false)
            _link.value = LinkUi.Idle
        }
    }

    // ---------- updates ----------

    fun checkUpdate() = viewModelScope.launch { g.updates.check() }
    fun installUpdate(r: AppRelease) = viewModelScope.launch { g.updates.downloadAndInstall(r) }
    fun dismissUpdate() = g.updates.dismiss()
    fun updatePermissionIntent() = g.updates.permissionIntent()
}
