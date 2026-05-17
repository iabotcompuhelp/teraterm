package com.opentermx.app.ui.dialog

import com.opentermx.app.ui.terminal.highlight.CustomHighlightRule
import com.opentermx.app.ui.terminal.highlight.MergePolicy
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ColorPicker
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import java.util.UUID

/**
 * Editor de una sola `CustomHighlightRule`. Para "Agregar", se invoca sin seed; para
 * "Editar", se pasa la regla existente. Devuelve la regla resultante (con el mismo `id`
 * en edit, o uno nuevo en add) o `null` si Cancel.
 *
 * Features clave:
 *  - Validación inline de regex: si no compila, label rojo bajo el campo.
 *  - Preview en vivo sobre un texto de muestra editable: las coincidencias se marcan en
 *    negrita con el color elegido (sin overhead de instanciar el motor completo).
 *  - ColorPicker para fg y bg (bg con checkbox "sin fondo" para dejarlo null).
 *  - Spinner de prioridad (menor = más prioritario, hint en el label).
 */
class HighlightRuleEditorDialog(
    seed: CustomHighlightRule? = null,
    initialSampleText: String = "Interface FastEthernet0/1 is up, line protocol is up",
) : Dialog<CustomHighlightRule>() {

    private val nameField = TextField(seed?.name.orEmpty()).apply {
        promptText = "Nombre descriptivo (ej. Equipos críticos)"
    }
    private val patternField = TextField(seed?.pattern.orEmpty()).apply {
        promptText = """\bCRITICAL\b"""
    }
    private val patternError = Label().apply {
        textFill = Color.web("#c0392b")
        font = Font.font(11.0)
    }
    private val fgPicker = ColorPicker(hexOrDefault(seed?.fgRgb, "#FF5733"))
    private val bgEnabledCheck = CheckBox("Color de fondo").apply { isSelected = seed?.bgRgb != null }
    private val bgPicker = ColorPicker(hexOrDefault(seed?.bgRgb, "#000000")).apply {
        disableProperty().bind(bgEnabledCheck.selectedProperty().not())
    }
    private val policyCombo = ComboBox<MergePolicy>().apply {
        items.setAll(MergePolicy.entries)
        value = seed?.mergePolicy ?: MergePolicy.RESPECT_SERVER
    }
    private val prioritySpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 9_999, seed?.priority ?: 200)
        isEditable = true
    }
    private val enabledCheck = CheckBox("Habilitada").apply { isSelected = seed?.enabled ?: true }
    private val sampleField = TextField(initialSampleText).apply { maxWidth = Double.MAX_VALUE }
    private val previewFlow = TextFlow().apply { padding = Insets(6.0) }

    private val ruleId: String = seed?.id ?: UUID.randomUUID().toString()

    init {
        title = "Regla de resaltado"
        headerText = if (seed == null) "Nueva regla" else "Editar regla"

        listOf(nameField, patternField, sampleField).forEach { it.maxWidth = Double.MAX_VALUE }

        // Recompute preview / validation on any change.
        patternField.textProperty().addListener { _, _, _ -> refreshValidationAndPreview() }
        sampleField.textProperty().addListener { _, _, _ -> refreshValidationAndPreview() }
        fgPicker.valueProperty().addListener { _, _, _ -> refreshPreview() }
        bgEnabledCheck.selectedProperty().addListener { _, _, _ -> refreshPreview() }
        bgPicker.valueProperty().addListener { _, _, _ -> refreshPreview() }

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 8.0
            padding = Insets(20.0)
            add(Label("Nombre:"), 0, 0); add(nameField, 1, 0, 2, 1)
            add(Label("Patrón (regex):"), 0, 1); add(patternField, 1, 1, 2, 1)
            add(patternError, 1, 2, 2, 1)
            add(Label("Color texto:"), 0, 3); add(fgPicker, 1, 3)
            add(Label("Color fondo:"), 0, 4); add(HBox(8.0, bgEnabledCheck, bgPicker), 1, 4, 2, 1)
            add(Label("Política:"), 0, 5); add(policyCombo, 1, 5)
            add(Label("Prioridad:"), 0, 6); add(prioritySpinner, 1, 6)
            add(enabledCheck, 1, 7)
            add(Label("Texto de prueba:"), 0, 8); add(sampleField, 1, 8, 2, 1)
            add(Label("Vista previa:"), 0, 9); add(previewFlow, 1, 9, 2, 1)
        }
        dialogPane.content = grid

        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        val okButton = dialogPane.lookupButton(ButtonType.OK)
        okButton.disableProperty().bind(
            nameField.textProperty().isEmpty
                .or(patternField.textProperty().isEmpty)
        )

        refreshValidationAndPreview()

        setResultConverter { btn ->
            if (btn == ButtonType.OK) buildResult() else null
        }
    }

    private fun buildResult(): CustomHighlightRule? {
        val regexValid = runCatching { Regex(patternField.text) }.isSuccess
        if (!regexValid) {
            patternError.text = "Regex inválida; ajustá antes de aceptar"
            return null
        }
        return CustomHighlightRule(
            id = ruleId,
            name = nameField.text.trim(),
            pattern = patternField.text,
            fgRgb = fgPicker.value.toHex(),
            bgRgb = if (bgEnabledCheck.isSelected) bgPicker.value.toHex() else null,
            mergePolicy = policyCombo.value ?: MergePolicy.RESPECT_SERVER,
            priority = prioritySpinner.value ?: 200,
            enabled = enabledCheck.isSelected,
        )
    }

    private fun refreshValidationAndPreview() {
        val compileResult = runCatching { Regex(patternField.text) }
        if (compileResult.isFailure) {
            patternError.text = "Regex inválida: ${compileResult.exceptionOrNull()?.message ?: "error de sintaxis"}"
            previewFlow.children.clear()
            previewFlow.children.add(Text("(corregí el patrón para ver el preview)"))
            return
        }
        patternError.text = ""
        refreshPreview()
    }

    private fun refreshPreview() {
        previewFlow.children.clear()
        val regex = runCatching { Regex(patternField.text, RegexOption.IGNORE_CASE) }.getOrNull() ?: return
        val text = sampleField.text.orEmpty()
        if (text.isEmpty()) return
        var pos = 0
        val fgColor = fgPicker.value
        val bgColor = if (bgEnabledCheck.isSelected) bgPicker.value else null
        for (match in regex.findAll(text)) {
            if (match.range.first > pos) {
                previewFlow.children.add(Text(text.substring(pos, match.range.first)))
            }
            val highlighted = Text(text.substring(match.range)).apply {
                fill = fgColor
                style = buildString {
                    append("-fx-font-weight: bold;")
                    bgColor?.let {
                        append(" -fx-background-color: ${it.toHex()};")
                    }
                }
            }
            previewFlow.children.add(highlighted)
            pos = match.range.last + 1
        }
        if (pos < text.length) {
            previewFlow.children.add(Text(text.substring(pos)))
        }
    }

    private fun hexOrDefault(hex: String?, fallback: String): Color = runCatching {
        Color.web(hex ?: fallback)
    }.getOrElse { Color.web(fallback) }

    private fun Color.toHex(): String = String.format(
        "#%02X%02X%02X",
        (red * 255).toInt().coerceIn(0, 255),
        (green * 255).toInt().coerceIn(0, 255),
        (blue * 255).toInt().coerceIn(0, 255),
    )
}
