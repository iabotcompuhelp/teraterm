"""schema_diff.py — Compara dos snapshots de schemas (entre branches o releases) y
reporta breaking changes en markdown listo para el changelog.

Considera breaking:
 - Una tool desaparece.
 - Un campo `required` desaparece (clientes que dejaron de mandarlo seguían funcionando).
   wait — al revés: agregar `required` rompe clientes que no lo mandaban. Revisamos ambos.
 - Un campo cambia de tipo (`string` → `integer`, etc.).
 - Un nuevo `required` aparece.
Considera no-breaking (lo loggea como `add`):
 - Tool nueva, campo nuevo opcional, descripción nueva.

Uso:
    python schema_diff.py before.json after.json > CHANGELOG.snippet.md
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Iterable

import click


def _tool_index(snapshot: dict | list) -> dict[str, dict]:
    tools = snapshot["tools"] if isinstance(snapshot, dict) and "tools" in snapshot else snapshot
    return {t["name"]: t for t in tools}


def _props(schema: dict) -> dict:
    return (schema or {}).get("properties", {}) or {}


def _required(schema: dict) -> set[str]:
    return set((schema or {}).get("required") or [])


def _diff_tool(name: str, before: dict, after: dict) -> list[tuple[str, str]]:
    """Devuelve lista de (severity, message). severity ∈ {breaking, add, info}."""
    out: list[tuple[str, str]] = []
    before_in, after_in = before.get("inputSchema", {}), after.get("inputSchema", {})

    # Required nuevos = breaking (clientes que no mandaban el campo van a fallar).
    new_required = _required(after_in) - _required(before_in)
    if new_required:
        out.append(("breaking", f"`{name}.inputSchema`: nuevos required {sorted(new_required)}"))

    # Required que desaparecen = no breaking, sólo info.
    removed_required = _required(before_in) - _required(after_in)
    if removed_required:
        out.append(("info", f"`{name}.inputSchema`: ya no es required {sorted(removed_required)}"))

    # Campos eliminados = breaking si estaban en input antes.
    removed_props = set(_props(before_in)) - set(_props(after_in))
    if removed_props:
        out.append(("breaking", f"`{name}.inputSchema`: campos eliminados {sorted(removed_props)}"))

    # Campos agregados = add.
    added_props = set(_props(after_in)) - set(_props(before_in))
    if added_props:
        out.append(("add", f"`{name}.inputSchema`: campos nuevos {sorted(added_props)}"))

    # Tipos cambiados = breaking.
    for prop in set(_props(before_in)) & set(_props(after_in)):
        bt = _props(before_in)[prop].get("type")
        at = _props(after_in)[prop].get("type")
        if bt != at:
            out.append((
                "breaking",
                f"`{name}.inputSchema.{prop}`: tipo cambió de `{bt}` a `{at}`",
            ))

    # Description: solo info.
    if before.get("description") != after.get("description"):
        out.append(("info", f"`{name}`: descripción cambiada"))

    return out


def _render_md(diffs: dict[str, list[tuple[str, str]]], removed: set[str], added: set[str]) -> str:
    lines = ["# MCP schema diff", ""]
    breaking: list[str] = []
    addn: list[str] = []
    info: list[str] = []
    for tool in removed:
        breaking.append(f"- Tool `{tool}` **eliminada**")
    for tool in added:
        addn.append(f"- Tool `{tool}` **nueva**")
    for tool, items in diffs.items():
        for severity, msg in items:
            entry = f"- {msg}"
            if severity == "breaking":
                breaking.append(entry)
            elif severity == "add":
                addn.append(entry)
            else:
                info.append(entry)
    if breaking:
        lines += ["## ⚠️ Breaking", *breaking, ""]
    if addn:
        lines += ["## ➕ Additions (no breaking)", *addn, ""]
    if info:
        lines += ["## ℹ️ Otros cambios", *info, ""]
    if not (breaking or addn or info):
        lines.append("Sin cambios.")
    return "\n".join(lines)


@click.command()
@click.argument("before", type=click.Path(exists=True, path_type=Path))
@click.argument("after", type=click.Path(exists=True, path_type=Path))
def main(before: Path, after: Path) -> None:
    """Compara schemas de tools entre dos snapshots y reporta breaking changes."""
    b = _tool_index(json.loads(before.read_text(encoding="utf-8")))
    a = _tool_index(json.loads(after.read_text(encoding="utf-8")))
    removed = set(b) - set(a)
    added = set(a) - set(b)
    diffs: dict[str, list[tuple[str, str]]] = {}
    has_breaking = False
    for name in sorted(set(b) & set(a)):
        items = _diff_tool(name, b[name], a[name])
        if items:
            diffs[name] = items
            if any(sev == "breaking" for sev, _ in items):
                has_breaking = True
    if removed:
        has_breaking = True
    sys.stdout.write(_render_md(diffs, removed, added))
    sys.stdout.write("\n")
    sys.exit(1 if has_breaking else 0)


if __name__ == "__main__":
    main()