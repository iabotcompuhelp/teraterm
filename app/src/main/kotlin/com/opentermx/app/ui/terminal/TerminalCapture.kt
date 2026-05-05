package com.opentermx.app.ui.terminal

import javafx.embed.swing.SwingFXUtils
import java.io.File
import javax.imageio.ImageIO

object TerminalCapture {

    fun captureToPng(view: TerminalView, file: File) {
        val image = view.snapshotImage()
        val buffered = SwingFXUtils.fromFXImage(image, null)
            ?: error("No se pudo convertir el snapshot a BufferedImage")
        if (!ImageIO.write(buffered, "png", file)) {
            error("ImageIO no soporta el formato PNG en este JRE")
        }
    }

    fun exportText(view: TerminalView, file: File) {
        file.writeText(view.exportText(), Charsets.UTF_8)
    }
}