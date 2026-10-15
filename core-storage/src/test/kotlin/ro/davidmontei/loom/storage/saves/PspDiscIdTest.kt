package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PspDiscIdTest {
    private fun umd(text: String) = PspDiscId.fromUmdData(text.toByteArray(Charsets.ISO_8859_1))

    @Test fun acceptsFourCapitalsAndFiveDigits() {
        assertThat(PspDiscId.parse("ULUS10064")).isEqualTo(PspDiscId("ULUS10064"))
        assertThat(PspDiscId.parse("NPUZ00001")!!.toString()).isEqualTo("NPUZ00001")
        assertThat(PspDiscId.parse(" UCES00001 ")).isEqualTo(PspDiscId("UCES00001"))
    }

    @Test fun dropsUmdDatasDash() {
        assertThat(umd("ULUS-10064|0123456789ABCDEF|0001|G")).isEqualTo(PspDiscId("ULUS10064"))
        assertThat(umd("ULUS10064|0123456789ABCDEF|0001|G")).isEqualTo(PspDiscId("ULUS10064"))
        assertThat(umd(" \tULUS-10064\t |0123456789ABCDEF")).isEqualTo(PspDiscId("ULUS10064"))
        // Only the file's own bytes count: a short file's sector is padded with zeros.
        assertThat(PspDiscId.fromUmdData("ULUS-10064|".toByteArray() + ByteArray(2037), length = 11)).isEqualTo(PspDiscId("ULUS10064"))
    }

    @Test fun refusesLowercaseOrOtherShapes() {
        for (text in listOf("ulus10064", "Ulus10064", "ULUS1006", "ULUS100640", "ULUS-10064", "ULU510064", "ULUS1006A", "", "ＵLUS10064")) {
            assertThat(PspDiscId.parse(text)).isNull()
        }
        assertThrows(IllegalArgumentException::class.java) { PspDiscId("ULUS-10064") }
        for (text in listOf("ulus-10064|x", "ULUS_10064|x", "ULUS--10064|", "ULUS-1006|xx", "ULUS-100645|", "HOMEBREW0|xx")) {
            assertThat(umd(text)).isNull()
        }
    }

    /** As Sigil: no `|` within the first 2048 bytes, or fewer than 10 bytes to look in, is no ID. */
    @Test fun umdDataWithoutItsFieldsHasNoId() {
        assertThat(umd("ULUS-10064")).isNull()
        assertThat(umd("ULUS10064|")).isEqualTo(PspDiscId("ULUS10064"))
        assertThat(PspDiscId.fromUmdData("ULUS10064|".toByteArray(), length = 9)).isNull()
        assertThat(umd("ULUS-10064" + " ".repeat(2038) + "|")).isNull()
    }
}
