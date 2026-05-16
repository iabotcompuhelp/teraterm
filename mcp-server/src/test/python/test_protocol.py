"""Tests del protocolo MCP (initialize, tools/list) — todavía sin tocar handlers."""

from __future__ import annotations

import pytest
from conftest import rpc


def test_initialize_devuelve_capabilities(client):
    resp = rpc(client, "initialize", {"clientInfo": {"name": "pytest", "version": "0"}})
    assert "error" not in resp or resp["error"] is None
    result = resp["result"]
    assert result["protocolVersion"]
    assert result["serverInfo"]["name"]
    assert "tools" in result["capabilities"]


def test_tools_list_expone_las_cuatro_tools_definidas(client):
    resp = rpc(client, "tools/list")
    tools = {t["name"] for t in resp["result"]["tools"]}
    assert tools == {"list_sessions", "inspect_session", "search_knowledge_base", "propose_commands"}


def test_metodo_desconocido_devuelve_method_not_found(client):
    resp = rpc(client, "no/existe")
    assert resp["error"]["code"] == -32601


def test_health_endpoint_devuelve_running(client):
    r = client.get("/mcp/health")
    r.raise_for_status()
    body = r.json()
    assert body["status"] == "RUNNING"
    assert body["tools"] == 4