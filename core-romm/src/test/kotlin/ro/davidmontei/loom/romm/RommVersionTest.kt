package ro.davidmontei.loom.romm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RommVersionTest {
    @Test fun parsesReleaseAndPrerelease() {
        assertThat(RommVersion.parse("5.3.1")).isEqualTo(RommVersion(5, 3, 1))
        assertThat(RommVersion.parse("v5.4.0-beta.1")).isEqualTo(RommVersion(5, 4, 0))
        assertThat(RommVersion.parse("5.4")).isEqualTo(RommVersion(5, 4, 0))
    }

    @Test fun developmentIsNewest() {
        assertThat(RommVersion.parse("development")!!).isGreaterThan(RommVersion(99, 0, 0))
    }

    @Test fun rejectsGarbage() {
        assertThat(RommVersion.parse("five")).isNull()
        assertThat(RommVersion.parse("1.2.3.4")).isNull()
    }

    @Test fun ordering() {
        assertThat(RommVersion(4, 9, 9)).isLessThan(MIN_SUPPORTED_ROMM)
        assertThat(RommVersion.parse(ROMM_CONTRACT_VERSION)!!).isAtLeast(MIN_SUPPORTED_ROMM)
        assertThat(RommVersion(5, 10, 0)).isGreaterThan(RommVersion(5, 9, 3))
    }

    @Test fun romm54IsWhereSendToDeviceStarts() {
        assertThat(RommVersion.parse("5.3.1")!!).isLessThan(ROMM_5_4)
        assertThat(RommVersion.parse("5.4.0")!!).isAtLeast(ROMM_5_4)
        assertThat(RommVersion.parse("5.4.0-beta.2")!!).isAtLeast(ROMM_5_4)
        assertThat(RommVersion.parse("development")!!).isAtLeast(ROMM_5_4)
        assertThat(RommVersion.parse(ROMM_CONTRACT_VERSION)!!).isAtLeast(ROMM_5_4)
    }
}
