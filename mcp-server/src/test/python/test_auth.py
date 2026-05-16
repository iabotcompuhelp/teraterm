"""Tests del bearer token. El servidor de tests siempre corre con un token activo."""

from __future__ import annotations

import httpx


def test_request_sin_token_devuelve_401(anon_client: httpx.Client):
    r = anon_client.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    assert r.status_code == 401


def test_request_con_token_invalido_devuelve_401(server):
    headers = {"Authorization": "Bearer wrong"}
    with httpx.Client(base_url=server["base"], headers=headers, timeout=10.0) as c:
        r = c.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    assert r.status_code == 401


def test_request_con_token_correcto_devuelve_200(client):
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    assert r.status_code == 200


def test_health_endpoint_tambien_protegido(anon_client: httpx.Client):
    r = anon_client.get("/mcp/health")
    assert r.status_code == 401