package com.opentermx.app.ui.terminal

import javafx.scene.paint.Color

object AnsiPalette {

    private val basicColors: Array<Color> = arrayOf(
        Color.web("#000000"), Color.web("#cd0000"), Color.web("#00cd00"), Color.web("#cdcd00"),
        Color.web("#1e90ff"), Color.web("#cd00cd"), Color.web("#00cdcd"), Color.web("#e5e5e5"),
        Color.web("#7f7f7f"), Color.web("#ff5555"), Color.web("#55ff55"), Color.web("#ffff55"),
        Color.web("#5c5cff"), Color.web("#ff55ff"), Color.web("#55ffff"), Color.web("#ffffff"),
    )

    private val palette: Array<Color> = Array(256) { i ->
        when {
            i < 16 -> basicColors[i]
            i < 232 -> {
                val n = i - 16
                Color.rgb(cubeStep(n / 36), cubeStep((n / 6) % 6), cubeStep(n % 6))
            }
            else -> {
                val v = 8 + (i - 232) * 10
                Color.rgb(v, v, v)
            }
        }
    }

    private fun cubeStep(v: Int): Int = if (v == 0) 0 else 55 + v * 40

    fun resolve(color: AnsiColor, defaultFg: Color, defaultBg: Color, isFg: Boolean): Color =
        when (color) {
            AnsiColor.Default -> if (isFg) defaultFg else defaultBg
            is AnsiColor.Indexed -> palette.getOrElse(color.index) { if (isFg) defaultFg else defaultBg }
            is AnsiColor.Rgb -> Color.rgb(
                color.r.coerceIn(0, 255),
                color.g.coerceIn(0, 255),
                color.b.coerceIn(0, 255),
            )
        }
}