package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.errors.GpsErrorKind
import io.github.chenchen913.baibai.core.errors.describeGpsError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 对应网页版 tests/gps.test.ts（1 项） */
class GpsErrorsTest {

    @Test
    fun `describeGpsError 错误码映射`() {
        assertEquals(GpsErrorKind.DENIED, describeGpsError(1)) // PERMISSION_DENIED
        assertEquals(GpsErrorKind.UNAVAILABLE, describeGpsError(2)) // POSITION_UNAVAILABLE
        assertEquals(GpsErrorKind.TIMEOUT, describeGpsError(3)) // TIMEOUT
        assertEquals(GpsErrorKind.UNAVAILABLE, describeGpsError(0))
        assertEquals(GpsErrorKind.UNAVAILABLE, describeGpsError(99))
    }
}
