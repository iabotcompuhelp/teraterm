# Firma de binarios de OpenTermX

Este documento describe cómo firmar/notarizar los instaladores generados por
`./gradlew packageMsi`, `jpackageMac`, `jpackageLinuxDeb` y `jpackageLinuxRpm`. Lo ejecuta
un humano con sus certificados; Claude Code no firma binarios.

## Windows — Authenticode

1. Obtener un cert `.pfx` válido para code-signing (EV cert recomendado para evitar
   SmartScreen warnings).
2. Generar el .msi: `./gradlew packageMsi`.
3. Firmar el .msi:
   ```powershell
   signtool.exe sign /f cert.pfx /p "MI_PASSWORD" /tr http://timestamp.digicert.com /td sha256 /fd sha256 build\jpackage\OpenTermX-<version>.msi
   ```
4. Verificar:
   ```powershell
   signtool.exe verify /pa /v build\jpackage\OpenTermX-<version>.msi
   ```

## macOS — codesign + notarytool

1. Tener un Apple Developer ID Application cert instalado en el llavero.
2. Generar el .dmg: `./gradlew jpackageMac`.
3. Firmar el .app dentro del .dmg:
   ```bash
   codesign --deep --force --options runtime --sign "Developer ID Application: TU NOMBRE (TEAMID)" build/jpackage-mac/OpenTermX.app
   ```
4. Crear un zip para enviar a notarización (Apple no acepta dmg directamente):
   ```bash
   ditto -c -k --keepParent build/jpackage-mac/OpenTermX.app OpenTermX.zip
   xcrun notarytool submit OpenTermX.zip --apple-id "tu@email.com" --team-id TEAMID --password "app-specific-password" --wait
   ```
5. Stapleamos el ticket de notarización al .dmg:
   ```bash
   xcrun stapler staple build/jpackage-mac/OpenTermX-<version>.dmg
   ```

## Linux — GPG signature para .deb y .rpm

Para .deb:
```bash
dpkg-sig --sign builder build/jpackage-deb/opentermx_<version>_amd64.deb
```

Para .rpm:
```bash
rpm --addsign build/jpackage-rpm/opentermx-<version>.x86_64.rpm
```

Asegurate de tener `~/.gnupg/` configurado con la clave de firma del proyecto.

## Verificación cruzada

Tras firmar, distribuir junto al binario:
- El hash SHA-256 (`shasum -a 256 <archivo>`).
- La clave pública GPG / cert público para que los usuarios puedan verificar.

## Notas

- Los CI builds NO firman; solo build local lo hace para evitar exponer credenciales en
  el pipeline. El upload a GitHub Releases se hace después del firmado manual.
- Si firmás builds nightly/dev, usar un cert distinto del cert de release para que no
  se confundan.