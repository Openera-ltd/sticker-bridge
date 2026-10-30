package tech.openera.stickerbridge.update

import android.content.Context
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import tech.openera.stickerbridge.data.AppRelease
import tech.openera.stickerbridge.data.FakeApi
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UpdateManagerTest {
    private lateinit var ctx: Context
    private lateinit var api: ReleaseApi

    class ReleaseApi(var release: AppRelease?, var apk: ByteArray = ByteArray(0), var fail: Boolean = false) : tech.openera.stickerbridge.data.StickerApi by FakeApi() {
        override suspend fun latestApp(): AppRelease? {
            if (fail) throw java.io.IOException("offline")
            return release
        }
        override suspend fun downloadTo(path: String, dest: File, onProgress: (Long, Long) -> Unit) {
            dest.writeBytes(apk)
            onProgress(apk.size.toLong(), apk.size.toLong())
        }
    }

    private fun release(code: Int, sha: String = "00") = AppRelease(code, "1.$code", "v1/app/apk/$code", sha, 10, "Fixes")

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        api = ReleaseApi(null)
    }

    @Test fun newerVersionIsOffered() = runBlocking {
        api.release = release(5)
        val m = UpdateManager(ctx, api, currentVersion = 1)
        assertNotNull(m.check())
        assertTrue(m.state.value is UpdateState.Available)
    }

    @Test fun sameOrOlderVersionIsUpToDate() = runBlocking {
        api.release = release(1)
        val m = UpdateManager(ctx, api, currentVersion = 1)
        assertNull(m.check())
        assertEquals(UpdateState.UpToDate, m.state.value)
        api.release = null
        assertNull(m.check())
        assertEquals(UpdateState.UpToDate, m.state.value)
    }

    @Test fun offlineCheckFailsQuietly() = runBlocking {
        api.fail = true
        val m = UpdateManager(ctx, api, currentVersion = 1)
        assertNull(m.check())
        assertTrue(m.state.value is UpdateState.Failed)
    }

    @Test fun asksForInstallPermissionFirst() = runBlocking {
        shadowOf(ctx.packageManager).setCanRequestPackageInstalls(false)
        val r = release(2)
        val m = UpdateManager(ctx, api, currentVersion = 1)
        m.downloadAndInstall(r)
        assertEquals(UpdateState.NeedsPermission(r), m.state.value)
    }

    @Test fun damagedDownloadIsRejected() = runBlocking {
        shadowOf(ctx.packageManager).setCanRequestPackageInstalls(true)
        api.apk = "pretend apk".toByteArray()
        val r = release(2, sha = "deadbeef")
        val m = UpdateManager(ctx, api, currentVersion = 1)
        m.downloadAndInstall(r)
        val s = m.state.value
        assertTrue(s is UpdateState.Failed)
        assertEquals("The download was damaged. Try again.", (s as UpdateState.Failed).message)
    }

    @Test fun rightHashButNotAnAppIsRejected() = runBlocking {
        val f = File(ctx.cacheDir, "x").apply { writeBytes("pretend apk".toByteArray()) }
        val m = UpdateManager(ctx, api, currentVersion = 1)
        val problem = m.verify(f, release(2, sha = UpdateManager.sha256(f)))
        assertEquals("The download isn't a valid app.", problem)
    }

    @Test fun cancelledInstallOffersUpdateAgain() {
        val m = UpdateManager(ctx, api, currentVersion = 1)
        m.onInstallResult(PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertEquals(UpdateState.Idle, m.state.value)
        m.onInstallResult(PackageInstaller.STATUS_FAILURE_CONFLICT, "conflict")
        assertTrue(m.state.value is UpdateState.Failed)
    }

    @Test fun sha256IsHex() {
        val f = File(ctx.cacheDir, "h").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateManager.sha256(f))
    }
}
