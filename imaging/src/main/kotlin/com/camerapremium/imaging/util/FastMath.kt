package com.camerapremium.imaging.util

/** Float approximations used in per-pixel loops (errors ~1e-5, far below visibility). */
object FastMath {
    private const val INV_LN2 = 1.4426950408889634f

    fun log2(x: Float): Float {
        if (x <= 0f) return -126f
        val bits = java.lang.Float.floatToRawIntBits(x)
        val e = ((bits ushr 23) and 0xFF) - 127
        val m = java.lang.Float.intBitsToFloat((bits and 0x7FFFFF) or 0x3F800000)
        val t = (m - 1f) / (m + 1f)
        val t2 = t * t
        val ln = 2f * t * (1f + t2 * (0.33333334f + t2 * (0.2f + t2 * (0.14285715f + t2 * 0.11111111f))))
        return e + ln * INV_LN2
    }

    fun exp2(x: Float): Float {
        if (x < -125f) return 0f
        if (x > 127f) return Float.MAX_VALUE
        val fl = kotlin.math.floor(x)
        val i = fl.toInt()
        val f = x - fl
        val p = 1f + f * (0.6931472f + f * (0.2402265f + f * (0.05550411f + f * (0.009618129f + f * 0.0013333558f))))
        return p * java.lang.Float.intBitsToFloat((i + 127) shl 23)
    }
}
