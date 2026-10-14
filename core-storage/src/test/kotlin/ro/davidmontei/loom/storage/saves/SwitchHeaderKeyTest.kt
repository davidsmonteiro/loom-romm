package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** The owner's header key, read from a prod.keys file. The key here is the fixtures' made-up one. */
class SwitchHeaderKeyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val testKey = String(javaClass.getResourceAsStream("/switch/test-header-key.txt")!!.readBytes()).trim()

    private fun prodKeys(text: String): Path = tmp.root.toPath().resolve("prod.keys").also { Files.write(it, text.toByteArray()) }

    /** Whether [key] is the fixtures' key: it opens their card image. */
    private fun opensTheFixture(key: SwitchHeaderKey): Boolean {
        val image = tmp.root.toPath().resolve("game.xci")
        Files.write(image, javaClass.getResourceAsStream("/switch/game.xci")!!.readBytes())
        return SwitchTitleIdReader.read(image, key) is SwitchTitleIdResult.Found
    }

    /** prod.keys as dumped (every value here made up): many keys, `name = hex`, header_key among them, after names that start like it. */
    @Test fun readsTheHeaderKeyAmongTheOthers() {
        val file = prodKeys(
            "aes_kek_generation_source = ${"0123456789abcdef".repeat(2)}\r\n" +
                "header_kek_source = ${"fedcba9876543210".repeat(2)}\n" +
                "header_key_source = ${"00112233445566778899aabbccddeeff".repeat(2)}\n" +
                "header_key = ${testKey.uppercase()}\r\n" +
                "key_area_key_application_00 = ${"a5".repeat(16)}\n",
        )
        val key = SwitchHeaderKey.fromProdKeys(file)!!
        key.use { assertThat(opensTheFixture(it)).isTrue() }
    }

    @Test fun spacingAndCaseDontMatter() {
        SwitchHeaderKey.fromProdKeys(prodKeys("  header_key=\t$testKey  \n"))!!.use { assertThat(opensTheFixture(it)).isTrue() }
    }

    @Test fun withoutAUsableHeaderKeyThereIsNone() {
        val unusable = listOf(
            "", // empty
            "header_key_source = $testKey\n", // another key
            "header_key = ${testKey.dropLast(1)}\n", // 63 digits
            "header_key = ${testKey.dropLast(1)}g\n", // not hex
            "header_key = ${testKey}00\n", // too long
            "# header_key = $testKey\n", // commented out
            "header_key $testKey\n", // no "="
        )
        for (text in unusable) assertThat(SwitchHeaderKey.fromProdKeys(prodKeys(text))).isNull()
    }

    @Test fun aMissingFileThrows() {
        assertThrows(java.io.IOException::class.java) { SwitchHeaderKey.fromProdKeys(tmp.root.toPath().resolve("prod.keys")) }
    }

    /** The key never becomes text, and once closed it is gone. */
    @Test fun theKeyIsNeverShownAndIsWipedWhenClosed() {
        val key = SwitchHeaderKey.fromProdKeys(prodKeys("header_key = $testKey\n"))!!
        assertThat(key.toString()).doesNotContain(testKey.take(8))
        assertThat(key.toString()).doesNotContain(testKey.take(8).uppercase())
        key.close()
        assertThrows(IllegalStateException::class.java) { opensTheFixture(key) }
    }

    /** Closing re-keys the ciphers made from the key with zeros, so they no longer hold it once let go. */
    @Test fun closingReKeysTheCiphers() {
        val key = SwitchHeaderKey.fromProdKeys(prodKeys("header_key = $testKey\n"))!!
        assertThat(opensTheFixture(key)).isTrue()
        val (decrypt, encrypt) = key.ciphersInUse!!
        key.close()
        val zero = SecretKeySpec(ByteArray(16), "AES")
        val block = ByteArray(16) { it.toByte() }
        assertThat(decrypt.doFinal(block)).isEqualTo(Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, zero) }.doFinal(block))
        assertThat(encrypt.doFinal(block)).isEqualTo(Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, zero) }.doFinal(block))
        assertThat(key.ciphersInUse).isNull()
    }

    /** IEEE 1619's AES-XTS test vector 2 (whose data unit number is little-endian, as the standard writes it). */
    @Test fun xtsMatchesTheStandardsTestVector() {
        val data = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(ByteArray(16) { 0x11 }, "AES")) }
        val tweak = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(16) { 0x22 }, "AES")) }
        val unit = ByteArray(16).also { for (i in 0 until 5) it[i] = 0x33 }
        val text = "c454185e6a16936e39334038acef838bfb186fff7480adc4289382ecd6d394f0".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        Xts.decrypt(data, tweak, unit, text, 0, text.size)
        assertThat(text.all { it == 0x44.toByte() }).isTrue()
    }

    /** Vector 4: a whole 512-byte sector, 32 blocks, so every step of the tweak (and its reduction) counts. */
    @Test fun xtsDecryptsAWholeSectorAsTheStandardDoes() {
        fun aes(mode: Int, hex: String) = Cipher.getInstance("AES/ECB/NoPadding")
            .apply { init(mode, SecretKeySpec(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "AES")) }
        val text = String(javaClass.getResourceAsStream("/switch/xts-ieee1619-vector4.txt")!!.readBytes()).trim()
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertThat(text.size).isEqualTo(512)
        Xts.decrypt(aes(Cipher.DECRYPT_MODE, "27182818284590452353602874713526"), aes(Cipher.ENCRYPT_MODE, "31415926535897932384626433832795"),
            ByteArray(16), text, 0, text.size)
        assertThat(text).isEqualTo(ByteArray(512) { it.toByte() })
    }
}
