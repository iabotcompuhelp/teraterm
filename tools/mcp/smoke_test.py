"""smoke_test.py — CLI que ejecuta una llamada a cada tool del servidor MCP de OpenTermX
y valida que la respuesta llegue en < 2s. Pensado para correr post-deploy o tras un
restart del servidor.

Exit code 0 si todas las tools responden a tiempo y sin `isError`. Exit code != 0 en
cuanto algo falle (timeout, HTTP error, JSON-RPC error, isError=true, schema mismatch).

Uso:
    python smoke_test.py --url http://127.0.0.1:8765 --token MI_TOKEN
    python smoke_test.py --url http://localhost:8765   # sin auth
"""

from __future__ import annotations

import json
import sys
import time
from dataclasses import dataclass

import click
import httpx


TIMEOUT_SECONDS = 2.0
"""Threshold de slowness. Si una tool tarda más, el smoke test falla."""


@dataclass
class Step:
    name: str
    method: str
    params: dict | None
    expect_tool_call_ok: bool = False


def _call(client: httpx.Client, req_id: int, method: str, params: dict | None) -> dict:
    body = {"jsonrpc": "2.0", "id": req_id, "method": method}
    if params is not None:
        body["params"] = params
    r = client.post("/mcp", json=body, timeout=TIMEOUT_SECONDS + 1.0)
    r.raise_for_status()
    return r.json()


def _step_ok(label: str, elapsed: float) -> None:
    click.echo(click.style(f"  OK    {label}  ({elapsed*1000:.0f}ms)", fg="green"))


def _step_fail(label: str, detail: str) -> None:
    click.echo(click.style(f"  FAIL  {label}: {detail}", fg="red"))


@click.command()
@click.option("--url", default="http://127.0.0.1:8765", help="Base URL del servidor MCP.")
@click.option("--token", default=None, help="Bearer token. Omitir si el servidor no exige auth.")
@click.option(
    "--session",
    default=None,
    help="ID de una sesión activa para inspect_session/propose_commands. Si no se pasa, esos pasos se saltean.",
)
def main(url: str, token: str | None, session: str | None) -> None:
    """Pega un health-check + ejecuta una llamada a cada tool del servidor MCP."""
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    steps: list[Step] = [
        Step("ping", "ping", None),
        Step("tools/list", "tools/list", None),
        Step(
            "list_sessions",
            "tools/call",
            {"name": "list_sessions", "arguments": {}},
            expect_tool_call_ok=True,
        ),
        Step(
            "search_knowledge_base",
            "tools/call",
            {"name": "search_knowledge_base", "arguments": {"query": "smoke test"}},
            expect_tool_call_ok=True,
        ),
    ]
    if session:
        steps += [
            Step(
                "inspect_session",
                "tools/call",
                {"name": "inspect_session", "arguments": {"sessionId": session, "lastLines": 10}},
                expect_tool_call_ok=True,
            ),
        ]
    else:
        click.echo(click.style("  SKIP  inspect_session  (--session no provisto)", fg="yellow"))
        click.echo(click.style("  SKIP  propose_commands (--session no provisto)", fg="yellow"))

    failures = 0
    with httpx.Client(base_url=url, headers=headers) as client:
        # Health endpoint
        try:
            t0 = time.monotonic()
            r = client.get("/mcp/health", timeout=TIMEOUT_SECONDS + 1.0)
            elapsed = time.monotonic() - t0
            r.raise_for_status()
            body = r.json()
            if body.get("status") != "RUNNING":
                _step_fail("health", f"status={body.get('status')}")
                failures += 1
            elif elapsed > TIMEOUT_SECONDS:
                _step_fail("health", f"slow ({elapsed*1000:.0f}ms > {TIMEOUT_SECONDS*1000:.0f}ms)")
                failures += 1
            else:
                _step_ok(f"health (server={body.get('server')} v{body.get('version')})", elapsed)
        except Exception as exc:
            _step_fail("health", str(exc))
            failures += 1

        for i, step in enumerate(steps, start=1):
            try:
                t0 = time.monotonic()
                resp = _call(client, i, step.method, step.params)
                elapsed = time.monotonic() - t0
                if elapsed > TIMEOUT_SECONDS:
                    _step_fail(step.name, f"slow ({elapsed*1000:.0f}ms)")
                    failures += 1
                    continue
                if resp.get("error"):
                    _step_fail(step.name, f"jsonrpc error: {resp['error']}")
                    failures += 1
                    continue
                if step.expect_tool_call_ok:
                    result = resp.get("result", {})
                    if result.get("isError"):
                        text = result.get("content", [{}])[0].get("text", "")
                        _step_fail(step.name, f"isError=true: {text}")
                        failures += 1
                        continue
                _step_ok(step.name, elapsed)
            except Exception as exc:
                _step_fail(step.name, str(exc))
                failures += 1

    if failures:
        click.echo(click.style(f"\n{failures} step(s) fallaron.", fg="red"))
        sys.exit(failures)
    click.echo(click.style("\nSmoke test verde: todas las tools responden bien.", fg="green"))


if __name__ == "__main__":
    main()