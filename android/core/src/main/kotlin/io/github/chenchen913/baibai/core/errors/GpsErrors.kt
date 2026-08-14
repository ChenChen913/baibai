package io.github.chenchen913.baibai.core.errors

/** 定位错误分类（对应网页版 gps.ts 的 describeGpsError） */
enum class GpsErrorKind {
    UNSUPPORTED,
    DENIED,
    UNAVAILABLE,
    TIMEOUT,
}

fun describeGpsError(code: Int): GpsErrorKind = when (code) {
    1 -> GpsErrorKind.DENIED
    2 -> GpsErrorKind.UNAVAILABLE
    3 -> GpsErrorKind.TIMEOUT
    else -> GpsErrorKind.UNAVAILABLE
}
