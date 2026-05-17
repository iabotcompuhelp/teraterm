package com.opentermx.app.ui.terminal.highlight

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Configuración persistida del resaltado visual. Se almacena en `AppSettings.highlight`.
 *
 * Defaults: feature ON con toggles individuales prendidos. Para que el operador no tenga
 * que activar nada manualmente — el valor de un terminal aumentado se ve desde la primera
 * sesión. Si quiere comportamiento legacy (sin highlight), destilda `enabled` y la sesión
 * SSH se comporta idéntica a la versión previa (criterio 7.7).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class HighlightSettings(
    /** Toggle global. Cuando es `false`, el engine retorna `LineOverlay.EMPTY` siempre. */
    val enabled: Boolean = true,
    /** Toggle para detección de prompt (colorea el sufijo `>`/`#`/`$`/`(config)#`). */
    val promptDetectionEnabled: Boolean = true,
    /** Toggle para keywords built-in (error/warning/up/down/etc). */
    val keywordsEnabled: Boolean = true,
    /** Toggle para resaltar comandos (show/configure/interface/ping/etc). */
    val commandsEnabled: Boolean = true,
    /** Reglas custom del operador. Vacío por default. */
    val customRules: List<CustomHighlightRule> = emptyList(),
    /**
     * Cuando `true`, no se aplica overlay mientras la app remota está en alternate screen
     * buffer (vim/top/less/htop). Default true — recomendado: estas apps tienen su propio
     * esquema de colores y nuestro overlay las rompería visualmente.
     */
    val skipOnAlternateScreen: Boolean = true,
)
