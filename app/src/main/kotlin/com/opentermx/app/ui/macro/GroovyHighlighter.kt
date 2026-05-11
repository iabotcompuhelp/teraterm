package com.opentermx.app.ui.macro

import org.fxmisc.richtext.model.StyleSpans
import org.fxmisc.richtext.model.StyleSpansBuilder
import java.util.regex.Pattern

object GroovyHighlighter {

    private const val KEYWORDS = "if|else|while|for|def|return|true|false|null|in|switch|case|" +
        "break|continue|try|catch|finally|throw|new|class|do|extends|implements|var|let|" +
        "static|public|private|protected|final"

    private const val FUNCTIONS = "sendln|send|waitfor|pause|messagebox|inputbox|filelog|log|" +
        "getClipboard|setClipboard|connect|disconnect|" +
        "tftp_put|tftp_get|tftp_server_start|tftp_server_stop|" +
        "ai_ask|ai_execute"

    private val pattern: Pattern = Pattern.compile(
        "(?<COMMENT>//[^\\n]*)" +
            "|(?<STRING>\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')" +
            "|(?<NUMBER>\\b\\d+(?:\\.\\d+)?\\b)" +
            "|(?<FUNC>\\b(?:$FUNCTIONS)\\b)" +
            "|(?<KEYWORD>\\b(?:$KEYWORDS)\\b)"
    )

    fun highlight(text: String): StyleSpans<Collection<String>> {
        val builder = StyleSpansBuilder<Collection<String>>()
        val matcher = pattern.matcher(text)
        var lastEnd = 0
        while (matcher.find()) {
            val styleClass = when {
                matcher.group("COMMENT") != null -> "macro-comment"
                matcher.group("STRING") != null -> "macro-string"
                matcher.group("NUMBER") != null -> "macro-number"
                matcher.group("FUNC") != null -> "macro-func"
                matcher.group("KEYWORD") != null -> "macro-keyword"
                else -> null
            }
            if (styleClass == null) continue
            builder.add(emptyList(), matcher.start() - lastEnd)
            builder.add(listOf(styleClass), matcher.end() - matcher.start())
            lastEnd = matcher.end()
        }
        builder.add(emptyList(), text.length - lastEnd)
        return builder.create()
    }
}