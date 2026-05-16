# Release checklist — OpenTermX

Procedimiento para cortar una release pública. Ejecutado por un humano, no por Claude Code.

## Pre-release

1. **Versionar:** confirmar que `build.gradle.kts` tiene la versión correcta (sin
   `-SNAPSHOT` para releases públicas).
2. **CHANGELOG.md:** verificar que la sección de la versión a cortar está completa.
3. **Build full:** `./gradlew clean build` — debe pasar en verde.
4. **Tests:** `./gradlew :mcp-server:check` corre unit (Kotlin) + integration (pytest).
5. **Smoke test:** levantar OpenTermX, habilitar MCP, correr:
   ```bash
   python tools/mcp/smoke_test.py --url http://127.0.0.1:8765 --token TOKEN
   ```
6. **Schema diff vs release anterior:** si rompe contratos, bump major:
   ```bash
   python tools/mcp/schema_diff.py last-release-schemas.json current-schemas.json
   ```

## Validación con clientes reales

Seguir `docs/mcp/CLIENT_TEST_PLAN.md`. Llenar la tabla de resultados con fecha + estado:

```
[YYYY-MM-DD] Claude Desktop = OK
[YYYY-MM-DD] Cursor         = OK
[YYYY-MM-DD] Cline          = OK
[YYYY-MM-DD] Continue       = OK | SKIP (HTTP no soportado) → usar stdio proxy
```

Cualquier FAIL bloquea la release.

## Build de instaladores

Cada plataforma se buildea en su propio host:

```bash
# Linux:
./gradlew :app:jpackageLinuxDeb :app:jpackageLinuxRpm
# Mac (en macOS):
./gradlew :app:jpackageMac
# Windows (en Windows con WiX 3.14):
./gradlew :app:packageMsi
```

Outputs:
- `app/build/jpackage/OpenTermX-<version>.msi`
- `app/build/jpackage-mac/OpenTermX-<version>.dmg`
- `app/build/jpackage-deb/opentermx_<version>_amd64.deb`
- `app/build/jpackage-rpm/opentermx-<version>.x86_64.rpm`

## Firma

Seguir `docs/release/SIGNING.md`. Después de firmar:

```bash
shasum -a 256 build/jpackage*/OpenTermX-*  > SHA256SUMS.txt
```

## Publicar GitHub Release

1. Crear tag firmado: `git tag -s v1.0.0 -m "OpenTermX 1.0.0"`.
2. `git push origin v1.0.0`.
3. `gh release create v1.0.0 --notes-file CHANGELOG.md` con todos los artefactos:
   ```bash
   gh release create v1.0.0 \
     --title "OpenTermX 1.0.0" \
     --notes-file CHANGELOG.md \
     app/build/jpackage/*.msi \
     app/build/jpackage-mac/*.dmg \
     app/build/jpackage-deb/*.deb \
     app/build/jpackage-rpm/*.rpm \
     SHA256SUMS.txt
   ```

## Post-release

1. Bump versión en `build.gradle.kts` a la próxima `-SNAPSHOT` para evitar releases
   accidentales del próximo commit.
2. Anunciar release en el README + canal interno.
3. Capturar screenshots reales para `docs/mcp/USER_GUIDE.md` reemplazando los placeholders
   `![screenshot: ...]`.