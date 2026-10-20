package app.nullpointr.foxnav.shared.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Colours as 0xRRGGBB: contrast (WCAG 2), mixing, HSV, and keeping colours readable. */
object ColorMath {
    const val WHITE = 0xFFFFFF

    /** The ink the palettes use for dark text on bright colours. */
    const val INK = 0x0B0D12

    fun parseHex(text: String): Int? =
        text.trim().removePrefix("#").takeIf { it.length == 6 && it.all { char -> char.isDigit() || char.lowercaseChar() in 'a'..'f' } }?.toInt(16)

    fun hex(rgb: Int): String = "#" + (rgb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    fun red(rgb: Int) = rgb shr 16 and 0xFF

    fun green(rgb: Int) = rgb shr 8 and 0xFF

    fun blue(rgb: Int) = rgb and 0xFF

    fun rgb(red: Int, green: Int, blue: Int): Int = (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)

    /** WCAG relative luminance, 0 (black) to 1 (white). */
    fun luminance(rgb: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(red(rgb)) + 0.7152 * channel(green(rgb)) + 0.0722 * channel(blue(rgb))
    }

    /** WCAG contrast ratio, 1 to 21. */
    fun contrast(a: Int, b: Int): Double {
        val (light, dark) = luminance(a).let { la -> luminance(b).let { lb -> max(la, lb) to min(la, lb) } }
        return (light + 0.05) / (dark + 0.05)
    }

    /** [from] towards [to] by [amount] (0 to 1), channel by channel. */
    fun mix(from: Int, to: Int, amount: Double): Int {
        fun channel(a: Int, b: Int) = (a + (b - a) * amount).roundToInt()
        return rgb(channel(red(from), red(to)), channel(green(from), green(to)), channel(blue(from), blue(to)))
    }

    /** Hue (0–360), saturation and value (0–1). */
    fun toHsv(rgb: Int): Triple<Double, Double, Double> {
        val r = red(rgb) / 255.0
        val g = green(rgb) / 255.0
        val b = blue(rgb) / 255.0
        val high = max(r, max(g, b))
        val delta = high - min(r, min(g, b))
        val hue = when {
            delta == 0.0 -> 0.0
            high == r -> 60 * (((g - b) / delta).mod(6.0))
            high == g -> 60 * ((b - r) / delta + 2)
            else -> 60 * ((r - g) / delta + 4)
        }
        return Triple(hue, if (high == 0.0) 0.0 else delta / high, high)
    }

    fun fromHsv(hue: Double, saturation: Double, value: Double): Int {
        val h = hue.mod(360.0) / 60
        val c = value * saturation
        val x = c * (1 - abs(h.mod(2.0) - 1))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = value - c
        return rgb(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
    }

    /** White or ink, whichever reads better on [background]. */
    fun onColor(background: Int, light: Int = WHITE, dark: Int = INK): Int =
        if (contrast(light, background) >= contrast(dark, background)) light else dark

    /**
     * [rgb], made lighter on a dark [background] or darker on a light one, keeping its hue, until
     * it has [ratio] contrast against it (or as close as it gets).
     */
    fun withContrast(rgb: Int, background: Int, ratio: Double): Int {
        val towards = if (luminance(background) < 0.18) WHITE else 0x000000
        var color = rgb
        var step = 0
        while (contrast(color, background) < ratio && step < 50) {
            step++
            color = mix(rgb, towards, step / 50.0)
        }
        return color
    }

    /**
     * Whether [rgb] could pass for a warning colour: amber or red, the colours FoxNav keeps for
     * warnings (design/README.md). Pinks and magentas don't; nor do greys and dark colours.
     */
    fun looksLikeWarning(rgb: Int): Boolean {
        val (hue, saturation, value) = toHsv(rgb)
        return saturation >= 0.4 && value >= 0.35 && (hue >= 345 || hue <= 60)
    }
}
