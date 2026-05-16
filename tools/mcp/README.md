# `tools/mcp/` — Scripts de soporte para el servidor MCP

Conjunto de utilidades Python para operar con el módulo `mcp-server`. No son parte del
build del producto: viven al lado para uso manual y CI.

## Setup

```bash
cd tools/mcp
python -m venv .venv
.venv/Scripts/activate          # PowerShell: .venv\Scripts\Activate.ps1
pip install -e .
```

Instala las CLIs como `opentermx-mcp-smoke`, `opentermx-mcp-gendocs` y
`opentermx-mcp-schemadiff`. También podés correr los `.py` directamente con `python`.

## Scripts

### `smoke_test.py`

Pega un health-check + `tools/call` a cada tool del servidor y valida que respondan en
< 2 s. Útil post-deploy o tras un restart manual.

```bash
python smoke_test.py --url http://127.0.0.1:8765 --token MI_TOKEN
python smoke_test.py --url http://localhost:8765 --session session-cisco
```

Salida en colores: `OK` verde, `FAIL` rojo, `SKIP` amarillo. Exit code = cantidad de pasos
fallidos.

### `generate_docs.py`

Genera la sección **Tools** del `mcp-server/README.md` a partir de los schemas. Idempotente.

```bash
# Desde un servidor corriendo
python generate_docs.py --from-server http://127.0.0.1:8765 --token MI_TOKEN \
    --out ../../mcp-server/README.tools.md

# Desde un snapshot JSON (sin red)
python generate_docs.py --from-json schemas-v0.1.json --out tools.md
```

### `schema_diff.py`

Compara dos snapshots de schemas y reporta breaking changes en markdown listo para el
changelog. Útil al cortar release.

```bash
# Capturar snapshots
curl -s -X POST -H "Authorization: Bearer X" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  http://127.0.0.1:8765/mcp | jq .result > before.json
# ... cambios ...
curl -s ...                                              > after.json

python schema_diff.py before.json after.json > CHANGELOG.snippet.md
```

Exit code 1 si encontró breaking changes (útil en CI para impedir merge).

## Tip CI

Pipeline típico:

1. Build + deploy del módulo `mcp-server`.
2. Levantar el servidor en un staging port.
3. `python smoke_test.py --url <staging>` — gate de salud.
4. `python generate_docs.py --from-server <staging> --out mcp-server/README.tools.md` —
   commitear el resultado si cambió.
5. `python schema_diff.py last-release.json current.json` — abortar release si hay
   breaking sin minor bump.