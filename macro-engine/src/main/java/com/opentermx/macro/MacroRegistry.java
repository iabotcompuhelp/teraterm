package com.opentermx.macro;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registro filesystem-based de macros Groovy. Por convención, los macros viven en
 * {@code ~/.opentermx/macros/} con extensión {@code .groovy}. Cada archivo es un script
 * Groovy completo; los metadatos opcionales se declaran como comentarios mágicos en las
 * primeras líneas:
 *
 * <pre>{@code
 * //! macro: deploy_vlan
 * //! description: Crea una VLAN y le asigna interfaces
 * //! param: vlanId (int, required) — ID de la VLAN
 * //! param: name (string, optional) — Nombre para mostrar
 * configure terminal
 * vlan ${params.vlanId}
 * name ${params.name ?: "VLAN ${params.vlanId}"}
 * end
 * }</pre>
 *
 * Si no hay metadatos, el {@code name} se infiere del nombre del archivo y la
 * {@code description} queda vacía. Sin parámetros declarados, el handler MCP acepta
 * cualquier objeto como {@code parameters} y lo pasa al script tal cual.
 */
public final class MacroRegistry {

    private static final Pattern META = Pattern.compile("^\\s*//!\\s*(\\w+)\\s*:\\s*(.+?)\\s*$");
    private static final Pattern PARAM_LINE = Pattern.compile(
            "^(\\w+)\\s*\\((\\w+)(?:,\\s*(required|optional))?\\)\\s*(?:[—-]\\s*(.+))?$"
    );

    private final Path baseDir;

    public MacroRegistry(Path baseDir) {
        this.baseDir = baseDir;
    }

    /** Constructor por defecto que apunta a {@code ~/.opentermx/macros/}. */
    public MacroRegistry() {
        this(Path.of(System.getProperty("user.home"), ".opentermx", "macros"));
    }

    /** Devuelve la lista de macros disponibles. Si el directorio no existe, devuelve vacío. */
    public List<MacroInfo> list() {
        if (!Files.isDirectory(baseDir)) {
            return Collections.emptyList();
        }
        List<MacroInfo> out = new ArrayList<>();
        try (var stream = Files.list(baseDir)) {
            stream
                    .filter(p -> p.getFileName().toString().endsWith(".groovy"))
                    .sorted()
                    .forEach(p -> out.add(parseHeader(p)));
        } catch (IOException e) {
            // listing no debería romper el handler — devolvemos lo que tengamos.
            return out;
        }
        return out;
    }

    /** Devuelve el {@link MacroInfo} para un nombre, o {@code null} si no existe. */
    public MacroInfo lookup(String name) {
        for (MacroInfo info : list()) {
            if (info.name().equals(name)) return info;
        }
        return null;
    }

    /** Lee el script crudo (sin headers procesados). Útil para mostrar al operador. */
    public String readScript(MacroInfo info) throws IOException {
        return Files.readString(info.filePath());
    }

    private MacroInfo parseHeader(Path file) {
        String fallbackName = file.getFileName().toString().replaceFirst("\\.groovy$", "");
        String name = fallbackName;
        String description = "";
        List<ParamInfo> params = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file)) {
                Matcher m = META.matcher(line);
                if (!m.matches()) {
                    // Primera línea no-meta y no-en-blanco corta el parsing de headers.
                    if (!line.isBlank() && !line.trim().startsWith("//")) break;
                    continue;
                }
                String key = m.group(1);
                String value = m.group(2);
                switch (key) {
                    case "macro" -> name = value;
                    case "description" -> description = value;
                    case "param" -> {
                        Matcher pm = PARAM_LINE.matcher(value);
                        if (pm.matches()) {
                            params.add(new ParamInfo(
                                    pm.group(1),
                                    pm.group(2),
                                    "required".equalsIgnoreCase(pm.group(3)),
                                    pm.group(4) == null ? "" : pm.group(4)
                            ));
                        }
                    }
                    default -> { /* unknown header key — ignorar */ }
                }
            }
        } catch (IOException e) {
            description = "Error leyendo el archivo: " + e.getMessage();
        }
        return new MacroInfo(name, description, params, file);
    }

    public record MacroInfo(String name, String description, List<ParamInfo> parameters, Path filePath) {}
    public record ParamInfo(String name, String type, boolean required, String description) {}
}