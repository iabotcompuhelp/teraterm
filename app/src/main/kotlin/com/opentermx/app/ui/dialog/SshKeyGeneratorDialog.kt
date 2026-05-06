package com.opentermx.app.ui.dialog

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.opentermx.app.i18n.Strings
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class SshKeyGeneratorDialog(owner: Window) : Stage() {

    private val typeCombo = ComboBox<String>().apply {
        items.addAll("RSA", "ECDSA", "ED25519")
        value = "RSA"
        setOnAction { refreshSizeOptions() }
    }
    private val sizeCombo = ComboBox<Int>().apply {
        items.addAll(2048, 3072, 4096)
        value = 2048
    }
    private val passphraseField = PasswordField()
    private val commentField = TextField(System.getProperty("user.name") + "@opentermx")
    private val publicKeyArea = TextArea().apply {
        isEditable = false; prefRowCount = 5; isWrapText = true
    }
    private val statusLabel = Label(Strings["setup.keygen.idle"])

    private val generateButton = Button(Strings["setup.keygen.generate"])
    private val savePubButton = Button(Strings["setup.keygen.savePub"]).apply { isDisable = true }
    private val savePrivButton = Button(Strings["setup.keygen.savePriv"]).apply { isDisable = true }
    private val closeButton = Button(Strings["setup.keygen.close"]).apply { setOnAction { close() } }

    private var keyPair: KeyPair? = null

    init {
        title = Strings["setup.keygen.title"]
        initOwner(owner)
        initModality(Modality.NONE)
        refreshSizeOptions()

        generateButton.setOnAction { generate() }
        savePubButton.setOnAction { savePublicKey() }
        savePrivButton.setOnAction { savePrivateKey() }

        val grid = GridPane().apply {
            hgap = 10.0; vgap = 8.0; padding = Insets(16.0)
            var r = 0
            add(Label(Strings["setup.keygen.type"]), 0, r); add(typeCombo, 1, r); r++
            add(Label(Strings["setup.keygen.size"]), 0, r); add(sizeCombo, 1, r); r++
            add(Label(Strings["setup.keygen.passphrase"]), 0, r); add(passphraseField, 1, r); r++
            add(Label(Strings["setup.keygen.comment"]), 0, r); add(commentField, 1, r); r++
        }

        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        val buttons = HBox(8.0, generateButton, savePubButton, savePrivButton, spacer, closeButton)

        val layout = VBox(10.0,
            grid,
            Label(Strings["setup.keygen.publicKey"]),
            publicKeyArea,
            statusLabel,
            buttons,
        ).apply {
            padding = Insets(0.0, 16.0, 16.0, 16.0)
            VBox.setVgrow(publicKeyArea, Priority.ALWAYS)
            minWidth = 560.0; minHeight = 420.0
        }
        scene = Scene(layout)
    }

    private fun refreshSizeOptions() {
        when (typeCombo.value) {
            "RSA" -> {
                sizeCombo.items.setAll(2048, 3072, 4096)
                sizeCombo.value = 2048
                sizeCombo.isDisable = false
            }
            "ECDSA" -> {
                sizeCombo.items.setAll(256, 384, 521)
                sizeCombo.value = 256
                sizeCombo.isDisable = false
            }
            "ED25519" -> {
                sizeCombo.items.clear()
                sizeCombo.value = null
                sizeCombo.isDisable = true
            }
        }
    }

    private fun generate() {
        val type = when (typeCombo.value) {
            "RSA" -> KeyPair.RSA
            "ECDSA" -> KeyPair.ECDSA
            "ED25519" -> KeyPair.ED25519
            else -> KeyPair.RSA
        }
        val size = sizeCombo.value ?: 256
        try {
            val jsch = JSch()
            val kp = KeyPair.genKeyPair(jsch, type, size)
            val baos = ByteArrayOutputStream()
            kp.writePublicKey(baos, commentField.text.ifBlank { "opentermx" })
            keyPair = kp
            publicKeyArea.text = baos.toString(StandardCharsets.UTF_8)
            statusLabel.text = Strings.format("setup.keygen.generated",
                typeCombo.value + (if (size > 0) " ($size)" else ""))
            savePubButton.isDisable = false
            savePrivButton.isDisable = false
        } catch (ex: Exception) {
            keyPair = null
            statusLabel.text = Strings.format("setup.keygen.error", ex.message ?: ex.javaClass.simpleName)
            savePubButton.isDisable = true
            savePrivButton.isDisable = true
        }
    }

    private fun savePublicKey() {
        val kp = keyPair ?: return
        val file = FileChooser().apply {
            title = Strings["setup.keygen.savePub"]
            initialFileName = "id_${typeCombo.value.lowercase()}.pub"
        }.showSaveDialog(this) ?: return
        runCatching {
            kp.writePublicKey(file.absolutePath, commentField.text.ifBlank { "opentermx" })
        }.onSuccess { statusLabel.text = Strings.format("setup.keygen.saved", file.name) }
            .onFailure { statusLabel.text = Strings.format("setup.keygen.error", it.message ?: "") }
    }

    private fun savePrivateKey() {
        val kp = keyPair ?: return
        val file = FileChooser().apply {
            title = Strings["setup.keygen.savePriv"]
            initialFileName = "id_${typeCombo.value.lowercase()}"
        }.showSaveDialog(this) ?: return
        runCatching {
            val passphrase = passphraseField.text
            if (passphrase.isNotEmpty()) {
                kp.writePrivateKey(file.absolutePath, passphrase.toByteArray(StandardCharsets.UTF_8))
            } else {
                kp.writePrivateKey(file.absolutePath)
            }
        }.onSuccess { statusLabel.text = Strings.format("setup.keygen.saved", file.name) }
            .onFailure { statusLabel.text = Strings.format("setup.keygen.error", it.message ?: "") }
    }

    override fun hide() {
        keyPair?.dispose()
        keyPair = null
        super.hide()
    }
}