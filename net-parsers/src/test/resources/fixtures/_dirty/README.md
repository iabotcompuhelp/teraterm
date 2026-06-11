# Fixtures "sucios" (_dirty)

Estos archivos contienen el stream CRUDO tal como llega del socket, ANTES de limpiar.
Sirven para testear el `OutputCleaner` previo al parser. Errores del catalogo que cubren: #1-#7.

## cisco_ios_raw_pager_ansi_syslog.bin.txt
Contiene, deliberadamente:
- Eco del comando enviado en la primera linea (error #5)
- Secuencias ANSI: ESC[?25h y ESC[0m (error #3)
- Paginador ` --More-- ` seguido de 10 backspaces (\x08), 10 espacios y 10 backspaces,
  que es exactamente como IOS borra el --More-- al recibir espacio (error #1)
- Dos lineas de syslog asincrono (%LINK-3-UPDOWN / %LINEPROTO-5-UPDOWN) intercaladas (error #6)
- CRLF en vez de LF (error #4)
- Prompt final `SW-ACCESS-01#` que NO es parte del output

El resultado esperado tras limpiar esta en `cisco_ios_raw_pager_ansi_syslog.cleaned.txt`.
Reglas: quitar eco, ANSI, artefactos de paginador (texto + backspaces + relleno),
lineas de syslog (`^\*?\w{3}\s+\d+.*%[A-Z0-9_-]+-\d-`), prompt final, y normalizar CRLF->LF.

## huawei_raw_latin1_more.bin.txt
Contiene:
- Eco del comando
- Bytes 0xF3 y 0xA9 (Latin-1) invalidos en UTF-8: el decodificador debe usar
  reemplazo (U+FFFD) o fallback Latin-1, NUNCA lanzar excepcion (error #4)
- Paginador estilo Huawei `  ---- More ----`
- Prompt final `<HUA-CORE-01>`

No tiene .cleaned.txt: el test debe verificar que (a) no lanza excepcion al decodificar,
(b) el paginador y el prompt no aparecen en el resultado, (c) la linea `Speed : 1000, Duplex: FULL`
sobrevive intacta.
