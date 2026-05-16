"""Tests de error paths: body inválido, JSON malformado, notifications, etc."""

from __future__ import annotations


def test_body_vacio_devuelve_400_jsonRpc(client):
    r = client.post("/mcp", content=b"", headers={"Content-Type": "application/json"})
    assert r.status_code == 400
    body = r.json()
    assert body["error"]["code"] in (-32600, -32700)


def test_json_invalido_devuelve_parse_error(client):
    r = client.post("/mcp", content=b"{not json", headers={"Content-Type": "application/json"})
    assert r.status_code == 400
    body = r.json()
    assert body["error"]["code"] == -32700


def test_notification_devuelve_204(client):
    # Sin `id` → notification → 204 No Content
    r = client.post(
        "/mcp",
        json={"jsonrpc": "2.0", "method": "notifications/initialized"},
    )
    assert r.status_code == 204
    assert r.content == b""


def test_ping_devuelve_resultado_vacio(client):
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 99, "method": "ping"})
    r.raise_for_status()
    body = r.json()
    assert body["result"] == {}