"""Tests de integración Phase 3 Fase 2 — Device Registry.

Verifica inventory_list (con/sin filtros) e inventory_describe (con alias válido,
inválido). El server tiene 2 devices mock seedados en TestServerMain: `core-router-1`
(cisco_ios, tags=[core, lab]) y `edge-mk-1` (mikrotik_routeros, tags=[edge]).

También verifica que `open_session(deviceAlias=…)` recupera host/port/protocol del
inventario y no requiere `protocol`.
"""
from __future__ import annotations

import pytest

from conftest import call_tool


def test_inventory_list_devuelve_los_dos_devices_mock(client):
    resp = call_tool(client, "inventory_list", arguments={})
    devices = resp["result"]["structuredContent"]["devices"]
    aliases = {d["alias"] for d in devices}
    assert aliases == {"core-router-1", "edge-mk-1"}, f"aliases real: {aliases}"


def test_inventory_list_nunca_devuelve_credenciales(client):
    resp = call_tool(client, "inventory_list", arguments={})
    devices = resp["result"]["structuredContent"]["devices"]
    # Verificación robusta: ningún field con nombre sensible debe aparecer.
    sensitive = {"secret", "password", "passphrase", "keyPath", "privateKey"}
    for d in devices:
        leaked = sensitive & d.keys()
        assert not leaked, f"credenciales filtradas en inventory_list: {leaked} en device {d['alias']}"


def test_inventory_list_filtra_por_tags(client):
    resp = call_tool(client, "inventory_list", arguments={"tags": ["core"]})
    devices = resp["result"]["structuredContent"]["devices"]
    aliases = {d["alias"] for d in devices}
    assert aliases == {"core-router-1"}


def test_inventory_list_filtra_por_deviceType(client):
    resp = call_tool(client, "inventory_list", arguments={"deviceType": "mikrotik_routeros"})
    devices = resp["result"]["structuredContent"]["devices"]
    aliases = {d["alias"] for d in devices}
    assert aliases == {"edge-mk-1"}


def test_inventory_list_combinacion_filtros_vacia_es_AND(client):
    resp = call_tool(client, "inventory_list", arguments={
        "tags": ["core"],
        "deviceType": "mikrotik_routeros",  # no matchea con core-router-1
    })
    devices = resp["result"]["structuredContent"]["devices"]
    assert devices == [], f"devices real: {devices}"


def test_inventory_describe_alias_valido(client):
    resp = call_tool(client, "inventory_describe", arguments={"alias": "core-router-1"})
    payload = resp["result"]["structuredContent"]
    assert payload["device"]["alias"] == "core-router-1"
    assert payload["device"]["host"] == "router-cisco.lab"
    assert payload["device"]["deviceType"] == "cisco_ios"
    assert "secret" not in payload["device"]


def test_inventory_describe_alias_inexistente(client):
    resp = call_tool(client, "inventory_describe", arguments={"alias": "no-existe"})
    payload = resp["result"]["structuredContent"]
    assert payload["device"] is None
    assert payload["activeSessionId"] is None


def test_inventory_describe_marca_sesion_activa(client):
    # Las sessions mock del TestServerMain están en host="router-cisco.lab" port=22
    # y "mk.lab" port=23 — matchean los devices del inventory.
    resp = call_tool(client, "inventory_describe", arguments={"alias": "core-router-1"})
    payload = resp["result"]["structuredContent"]
    assert payload["device"]["hasActiveSession"] is True
    # session-cisco es el id del seed mock.
    assert payload["activeSessionId"] == "session-cisco", payload


def test_open_session_con_deviceAlias_resuelve_destino(client):
    # TestServerMain corre con auto-approve=1; el SessionOpener es NoOp en tests.
    # Esperamos approved=true pero la creación falla en el opener stub — lo importante
    # es que el handler resolvió el alias sin necesidad de `protocol` ni `host` explícitos.
    resp = call_tool(client, "open_session", arguments={"deviceAlias": "core-router-1"})
    payload = resp["result"].get("structuredContent")
    assert payload is not None, resp["result"]
    assert payload["approved"] is True, payload
    # El NoOp no abre nada, así que sessionId queda null y error trae mensaje del opener.
    assert payload["sessionId"] is None
    assert payload["error"], payload


def test_open_session_con_deviceAlias_inexistente_es_NOT_FOUND(client):
    resp = call_tool(client, "open_session", arguments={"deviceAlias": "no-existe"})
    assert resp["result"]["isError"] is True
    msg = resp["result"]["content"][0]["text"]
    assert "no-existe" in msg and "no existe en el inventario" in msg, msg


def test_open_session_sin_protocol_ni_alias_es_INVALID_ARGUMENT(client):
    resp = call_tool(client, "open_session", arguments={})
    assert resp["result"]["isError"] is True
    msg = resp["result"]["content"][0]["text"]
    assert "protocol" in msg, msg
