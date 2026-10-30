package tech.openera.stickerbridge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackBuilderTest {
    private fun s(i: Int, animated: Boolean = false) =
        LocalSticker("s$i", "s$i.webp", animated = animated, emojis = listOf("😀"), addedAt = i.toLong())

    @Test fun splitsIntoPacksOf30_oldestFirst() {
        val packs = PackBuilder.build((1..65).map { s(it) }, "My TikTok Stickers")
        assertEquals(listOf("static-1", "static-2", "static-3"), packs.map { it.id })
        assertEquals(listOf(30, 30, 5), packs.map { it.stickers.size })
        assertEquals("s1", packs[0].stickers.first().id)
        assertEquals(listOf("My TikTok Stickers", "My TikTok Stickers 2", "My TikTok Stickers 3"), packs.map { it.name })
    }

    @Test fun stillAndAnimatedNeverMix() {
        val packs = PackBuilder.build(listOf(s(1), s(2, true), s(3), s(4, true)), "P")
        assertEquals(2, packs.size)
        assertTrue(packs[0].stickers.none { it.animated })
        assertTrue(packs[1].stickers.all { it.animated })
        assertEquals("P · Animated", packs[1].name)
        assertTrue(packs[1].animated)
    }

    @Test fun packNeeds3StickersForWhatsApp() {
        val small = PackBuilder.build(listOf(s(1), s(2)), "P").single()
        assertFalse(small.ready)
        assertEquals(1, small.missing)
        val ok = PackBuilder.build(listOf(s(1), s(2), s(3)), "P").single()
        assertTrue(ok.ready)
        assertEquals(0, ok.missing)
    }

    @Test fun addingStickersKeepsEarlierPacksStable() {
        val before = PackBuilder.build((1..30).map { s(it) }, "P")
        val after = PackBuilder.build((1..31).map { s(it) }, "P")
        assertEquals(before[0].stickers, after[0].stickers)
        assertEquals(1, after[1].stickers.size)
    }

    @Test fun fingerprintChangesWithContentNameEmojiAndOutline() {
        val base = PackBuilder.build(listOf(s(1), s(2), s(3)), "P").single()
        val fp = PackBuilder.fingerprint(base, true)
        assertEquals(fp, PackBuilder.fingerprint(base, true))
        assertNotEquals(fp, PackBuilder.fingerprint(base, false))
        assertNotEquals(fp, PackBuilder.fingerprint(base.copy(name = "Q"), true))
        val emoji = base.copy(stickers = base.stickers.map { if (it.id == "s1") it.copy(emojis = listOf("🔥")) else it })
        assertNotEquals(fp, PackBuilder.fingerprint(emoji, true))
    }

    @Test fun longNamesAreCutToWhatsAppsLimit() {
        val p = PackBuilder.build(listOf(s(1)), "x".repeat(300)).single()
        assertEquals(128, p.name.length)
    }
}
