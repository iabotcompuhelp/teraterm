"""generate_docs.py — Introspección de schemas y generación de la sección 'Tools' del
`mcp-server/README.md` desde una plantilla Jinja2.

Dos fuentes posibles de schemas:
 1. `--from-server URL` (con `--token`): llama `tools/list` y usa la respuesta.
 2. `--from-json path`: lee un snapshot JSON exportado a mano (útil offline / CI).

Idempotente: dos ejecuciones contra la misma fuente producen el mismo archivo
(diff vacío). Si encontrás diffs cosméticos, ajustá la plantilla — no escribas a mano.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import click
import httpx
from jinja2 import Template


TEMPLATE = Template(
    """## Tools expuestas (autogenerado por `tools/mcp/generate_docs.py` — NO EDITAR A MANO)

{% for tool in tools %}### `{{ tool.name }}`

{{ tool.description }}

**Input schema:**
```json
{{ tool.input_schema_pretty }}
```

{% if tool.output_schema_pretty %}**Output schema:**
```json
{{ tool.output_schema_pretty }}
```
{% endif %}
{% endfor %}
"""
)


def _fetch_from_server(url: str, token: str | None) -> list[dict]:
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    body = {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}
    with httpx.Client(base_url=url, headers=headers, timeout=10.0) as c:
        r = c.post("/mcp", json=body)
        r.raise_for_status()
        return r.json()["result"]["tools"]


def _load_from_json(path: Path) -> list[dict]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    return raw["tools"] if isinstance(raw, dict) and "tools" in raw else raw


def _render(tools: list[dict]) -> str:
    prepared = []
    for tool in tools:
        prepared.append({
            "name": tool["name"],
            "description": tool.get("description", "").strip(),
            "input_schema_pretty": json.dumps(tool.get("inputSchema", {}), indent=2, ensure_ascii=False),
            "output_schema_pretty": json.dumps(tool["outputSchema"], indent=2, ensure_ascii=False)
                if "outputSchema" in tool else None,
        })
    return TEMPLATE.render(tools=prepared)


@click.command()
@click.option("--from-server", "from_server", default=None, help="Servidor MCP a consultar.")
@click.option("--token", default=None, help="Bearer token si el servidor lo exige.")
@click.option("--from-json", "from_json", type=click.Path(exists=True, path_type=Path), default=None,
              help="Snapshot JSON con tools.")
@click.option("--out", type=click.Path(path_type=Path), default=None,
              help="Archivo destino. Si no se pasa, escribe a stdout.")
def main(from_server: str | None, token: str | None, from_json: Path | None, out: Path | None) -> None:
    """Imprime la sección 'Tools' del README a partir de los schemas."""
    if (from_server is None) == (from_json is None):
        click.echo("Pasá --from-server o --from-json, no ambos.", err=True)
        sys.exit(2)
    tools = _fetch_from_server(from_server, token) if from_server else _load_from_json(from_json)
    rendered = _render(tools)
    if out:
        out.write_text(rendered, encoding="utf-8")
        click.echo(f"Escribí {len(rendered)} bytes en {out}.")
    else:
        sys.stdout.write(rendered)


if __name__ == "__main__":
    main()