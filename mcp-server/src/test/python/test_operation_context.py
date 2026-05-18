"""Tests de integración Phase 3 Fase 1.

Verifica el ciclo completo de Operation Context contra un servidor MCP real:
start_operation → tool_call ve el bloque inyectado → end_operation lo limpia.
Se ejerce sobre el wire de HTTP igual que un cliente externo.
"""
from __future__ import annotations

import json

from conftest import call_tool


def test_start_operation_devuelve_id_y_descripcion(client):
    resp = call_tool(client, "start_operation", arguments={
        "contextInline": {
            "operation": {"description": "test op pytest"},
            "scope": {"forbidden_commands": ["reload"]},
        },
    })
    payload = resp["result"]["structuredContent"]
    assert payload["operationId"].startswith("op-"), payload
    assert payload["description"] == "test op pytest"
    # Cerramos para no dejar la op viva entre tests.
    call_tool(client, "end_operation", arguments={"operationId": payload["operationId"]})


def test_inject_aparece_en_tool_call_subsecuente(client):
    started = call_tool(client, "start_operation", arguments={
        "contextInline": {
            "operation": {"description": "inject test"},
            "scope": {"devices": ["core-router-1"], "forbidden_commands": ["reload"]},
        },
    })
    op_id = started["result"]["structuredContent"]["operationId"]
    try:
        resp = call_tool(client, "list_sessions", arguments={})
        text = resp["result"]["content"][0]["text"]
        assert text.startswith(f"[OPERATION CONTEXT {op_id}]"), f"text real: {text[:200]}"
        assert "description: inject test" in text
        assert "scope.devices: core-router-1" in text
        assert "forbidden_commands: reload" in text
        # El payload original sigue presente después del separador `---`.
        body = text.split("---\n", 1)[1]
        json.loads(body)  # debe ser JSON válido
    finally:
        call_tool(client, "end_operation", arguments={"operationId": op_id})


def test_inject_desaparece_tras_end_operation(client):
    started = call_tool(client, "start_operation", arguments={
        "contextInline": {"operation": {"description": "ephemeral"}, "scope": {}},
    })
    op_id = started["result"]["structuredContent"]["operationId"]
    call_tool(client, "end_operation", arguments={"operationId": op_id})
    resp = call_tool(client, "list_sessions", arguments={})
    text = resp["result"]["content"][0]["text"]
    assert "[OPERATION CONTEXT" not in text, f"no debería haber bloque; text: {text[:200]}"


def test_double_start_falla_con_mensaje_claro(client):
    started = call_tool(client, "start_operation", arguments={
        "contextInline": {"operation": {"description": "first"}, "scope": {}},
    })
    op_id = started["result"]["structuredContent"]["operationId"]
    try:
        dup = call_tool(client, "start_operation", arguments={
            "contextInline": {"operation": {"description": "second"}, "scope": {}},
        })
        assert dup["result"]["isError"] is True
        msg = dup["result"]["content"][0]["text"]
        assert "operación activa" in msg, msg
    finally:
        call_tool(client, "end_operation", arguments={"operationId": op_id})


def test_current_operation_refleja_la_activa(client):
    started = call_tool(client, "start_operation", arguments={
        "contextInline": {"operation": {"description": "current test"}, "scope": {}},
    })
    op_id = started["result"]["structuredContent"]["operationId"]
    try:
        cur = call_tool(client, "current_operation", arguments={})
        payload = cur["result"]["structuredContent"]
        assert payload["operationId"] == op_id
        assert payload["context"]["operation"]["description"] == "current test"
    finally:
        call_tool(client, "end_operation", arguments={"operationId": op_id})


def test_current_operation_devuelve_null_sin_op_activa(client):
    cur = call_tool(client, "current_operation", arguments={})
    payload = cur["result"]["structuredContent"]
    assert payload["operationId"] is None
    assert payload["context"] is None


def test_schema_invalido_devuelve_error_legible(client):
    resp = call_tool(client, "start_operation", arguments={
        "contextInline": {
            "operation": {"id": "op con espacios", "description": "bad id"},
            "scope": {},
        },
    })
    assert resp["result"]["isError"] is True
    msg = resp["result"]["content"][0]["text"]
    assert "Operation context inválido" in msg, msg
