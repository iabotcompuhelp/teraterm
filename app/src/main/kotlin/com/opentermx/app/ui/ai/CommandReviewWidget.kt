package com.opentermx.app.ui.ai

import com.opentermx.ai.context.Vendor
import com.opentermx.ai.parse.CodeBlock
import com.opentermx.ai.safety.ClassifiedCommand
import com.opentermx.ai.safety.RiskClassifier
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.app.i18n.Strings
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox

/**
 * Resultado de la revisión: qué eligió el operador.
 */
sealed interface ReviewOutcome {
    data class Execute(val commands: List<String>, val risks: List<RiskLevel>) : ReviewOutcome
    object Rejected : ReviewOutcome
}

/**
 * Widget inline en el chat que muestra un bloque de comandos generado por la IA
 * con semáforo de riesgo por línea (spec v4 § Panel de Revisión, línea 243).
 *
 * Permite al operador:
 *  - Marcar/desmarcar comandos individuales
 *  - Editarlos antes de ejecutar
 *  - Ejecutar todos, ejecutar solo los seleccionados, o rechazar el bloque entero
 *  - Doble confirmación si alguno de los seleccionados está en ROJO
 *
 * El widget delega la ejecución al callback [onDecision].
 */
class CommandReviewWidget(
    block: CodeBlock,
    private val vendor: Vendor,
    private val onDecision: (ReviewOutcome) -> Unit,
) : VBox(6.0) {

    private val classifications: List<ClassifiedCommand> =
        RiskClassifier.classify(block.lines, vendor)

    private val rowBoxes: List<CommandRow> = classifications.mapIndexed { idx, c ->
        CommandRow(idx, c)
    }
    private val checkAllBox = CheckBox(Strings["ai.review.selectAll"]).apply {
        isSelected = true
        setOnAction {
            rowBoxes.forEach { it.checkBox.isSelected = isSelected }
        }
    }
    private val executeAllBtn = Button(Strings["ai.review.executeAll"])
    private val executeSelectedBtn = Button(Strings["ai.review.executeSelected"])
    private val editBtn = Button(Strings["ai.review.edit"])
    private val rejectBtn = Button(Strings["ai.review.reject"])
    private val copyBtn = Button(Strings["ai.review.copy"])

    private var decided: Boolean = false

    init {
        padding = Insets(10.0)
        style = "-fx-background-color: derive(-fx-base, 12%); -fx-background-radius: 10; -fx-border-color: derive(-fx-base, 25%); -fx-border-radius: 10;"

        val title = HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            val headerLbl = Label(Strings.format("ai.review.header", classifications.size)).apply {
                style = "-fx-font-weight: bold;"
            }
            val vendorLbl = Label(vendor.displayName).apply {
                style = "-fx-text-fill: derive(-fx-text-base-color, 30%); -fx-font-size: 10.5px;"
            }
            val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
            children += headerLbl
            children += vendorLbl
            children += spacer
            children += checkAllBox
        }

        children += title

        if (block.explanation.isNotBlank()) {
            children += Label(block.explanation).apply {
                isWrapText = true
                maxWidth = 360.0
                style = "-fx-text-fill: derive(-fx-text-base-color, 25%); -fx-font-size: 11px;"
            }
        }

        rowBoxes.forEach { children += it.root }

        val anyDangerous = classifications.any { it.risk == RiskLevel.DANGEROUS }
        if (anyDangerous) {
            children += Label(Strings["ai.review.dangerousWarning"]).apply {
                isWrapText = true
                style = "-fx-text-fill: #c62828; -fx-font-weight: bold; -fx-font-size: 11px;"
            }
        }

        executeAllBtn.setOnAction { decide(executeAll = true) }
        executeSelectedBtn.setOnAction { decide(executeAll = false) }
        rejectBtn.setOnAction {
            if (!decided) {
                decided = true
                disableAll()
                onDecision(ReviewOutcome.Rejected)
            }
        }
        editBtn.setOnAction { openEditDialog() }
        copyBtn.setOnAction {
            val content = ClipboardContent().apply { putString(rowBoxes.joinToString("\n") { it.lineText }) }
            Clipboard.getSystemClipboard().setContent(content)
        }

        children += HBox(6.0, executeAllBtn, executeSelectedBtn, editBtn, rejectBtn, copyBtn).apply {
            alignment = Pos.CENTER_LEFT
        }
    }

    private fun decide(executeAll: Boolean) {
        if (decided) return
        val selected = if (executeAll) rowBoxes else rowBoxes.filter { it.checkBox.isSelected }
        if (selected.isEmpty()) return
        val hasDangerous = selected.any { it.classified.risk == RiskLevel.DANGEROUS }
        if (hasDangerous && !confirmDangerous(selected.count { it.classified.risk == RiskLevel.DANGEROUS })) {
            return
        }
        decided = true
        disableAll()
        val commands = selected.map { it.lineText }
        val risks = selected.map { it.classified.risk }
        onDecision(ReviewOutcome.Execute(commands, risks))
    }

    private fun confirmDangerous(count: Int): Boolean {
        val alert = Alert(AlertType.WARNING).apply {
            title = Strings["ai.review.dangerousTitle"]
            headerText = Strings.format("ai.review.dangerousHeader", count)
            contentText = Strings["ai.review.dangerousBody"]
            buttonTypes.setAll(ButtonType.YES, ButtonType.CANCEL)
        }
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.YES
    }

    private fun openEditDialog() {
        if (decided) return
        val area = TextArea(rowBoxes.joinToString("\n") { it.lineText }).apply {
            prefRowCount = 12; prefColumnCount = 60; isWrapText = false
        }
        val alert = Alert(AlertType.CONFIRMATION).apply {
            title = Strings["ai.review.editTitle"]
            headerText = Strings["ai.review.editHeader"]
            dialogPane.content = area
            dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        }
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return
        val newLines = area.text.lines().filter { it.isNotBlank() }
        if (newLines.isEmpty()) return
        // Tras editar consideramos que el operador ya revisó: clasificamos y ejecutamos
        // directamente con doble confirmación si hay líneas DANGEROUS.
        val reclassified = RiskClassifier.classify(newLines, vendor)
        val hasDangerous = reclassified.any { it.risk == RiskLevel.DANGEROUS }
        if (hasDangerous && !confirmDangerous(reclassified.count { it.risk == RiskLevel.DANGEROUS })) {
            return
        }
        decided = true
        disableAll()
        onDecision(ReviewOutcome.Execute(newLines, reclassified.map { it.risk }))
    }

    private fun disableAll() {
        executeAllBtn.isDisable = true
        executeSelectedBtn.isDisable = true
        editBtn.isDisable = true
        rejectBtn.isDisable = true
        checkAllBox.isDisable = true
        rowBoxes.forEach { it.checkBox.isDisable = true }
    }

    private class CommandRow(val index: Int, val classified: ClassifiedCommand) {
        val checkBox = CheckBox().apply { isSelected = true }
        val lineText: String get() = classified.raw
        val root: HBox

        init {
            val swatch = Label(" ").apply {
                style = "-fx-background-color: ${classified.risk.colorHex}; -fx-background-radius: 4;"
                prefWidth = 12.0
                maxWidth = 12.0
                prefHeight = 18.0
            }
            val command = Label(classified.raw).apply {
                style = "-fx-font-family: 'Consolas','Menlo',monospace; -fx-font-size: 11.5px;"
            }
            val risk = Label(classified.risk.name).apply {
                style = "-fx-text-fill: ${classified.risk.colorHex}; -fx-font-size: 10px; -fx-font-weight: bold;"
            }
            val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
            root = HBox(8.0, checkBox, swatch, command, spacer, risk).apply {
                alignment = Pos.CENTER_LEFT
                padding = Insets(2.0, 4.0, 2.0, 4.0)
            }
        }
    }
}
