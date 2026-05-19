"""Tests de integración Phase 3 Fase 5 — Policy engine determinístico.

Flujo:
1. policy_load con YAML inline (rol COMPLIANCE).
2. policy_list verifica que está registrada.
3. snapshot_create del device-cisco mock para tener content.
4. policy_evaluate contra el snapshot → PASS/FAIL determinísticos.
5. policy_audit sobre la flota → reporte agregado.

Tests cubren:
- YAML inválido → INVALID_ARGUMENT.
- policy_evaluate sin snapshot previo → NOT_FOUND con mensaje accionable.
- Markdown rendering opt-in.
"""
from __future__ import annotations

import httpx
import pytest

from conftest import call_tool


@pytest.fixture
def compliance_client(server, negotiated_protocol_version) -> httpx.Client:
    headers = {
        "Authorization": f"Bearer {server['token']}",
        "MCP-Protocol-Version": negotiated_protocol_version,
        "X-OpenTermX-Role": "COMPLIANCE",
    }
    with httpx.Client(base_url=server["base"], headers=headers, timeout=15.0) as c:
        yield c


@pytest.fixture
def validator_client(server, negotiated_protocol_version) -> httpx.Client:
    headers = {
        "Authorization": f"Bearer {server['token']}",
        "MCP-Protocol-Version": negotiated_protocol_version,
        "X-OpenTermX-Role": "VALIDATOR",
    }
    with httpx.Client(base_url=server["base"], headers=headers, timeout=15.0) as c:
        yield c


SAMPLE_POLICY_YAML = """
policy:
  name: "test-policy"
  version: "1.0"
  applies_to:
    device_types: ["cisco_ios"]
rules:
  - id: "require-cisco-marker"
    severity: "high"
    type: "require"
    target: "running_config"
    pattern: "Cisco"
    message: "Falta el banner Cisco"
  - id: "no-telnet"
    severity: "high"
    type: "pattern_deny"
    target: "running_config"
    pattern: "telnet"
    message: "Telnet detectado"
"""


def test_compliance_carga_policy_y_aparece_en_list(compliance_client):
    load = call_tool(compliance_client, "policy_load", arguments={"yaml": SAMPLE_POLICY_YAML})
    payload = load["result"]["structuredContent"]
    assert payload["name"] == "test-policy"
    assert payload["ruleCount"] == 2

    listed = call_tool(compliance_client, "policy_list", arguments={})
    items = listed["result"]["structuredContent"]["policies"]
    names = {p["name"] for p in items}
    assert "test-policy" in names


def test_yaml_invalido_devuelve_INVALID_ARGUMENT(compliance_client):
    bad = """
        policy:
          name: "x"
        rules:
          - id: "x"
            severity: "high"
            type: "i_made_this_up"
            pattern: "x"
            message: "y"
    """
    resp = call_tool(compliance_client, "policy_load", arguments={"yaml": bad})
    assert resp["result"]["isError"] is True
    msg = resp["result"]["content"][0]["text"]
    assert "Policy inválida" in msg, msg


def test_operator_no_puede_policy_audit(client):
    resp = call_tool(client, "policy_audit", arguments={"policyName": "x"})
    assert resp["error"]["code"] == -32601, resp


def test_validator_evalua_policy_contra_snapshot(client, validator_client, compliance_client):
    # Compliance carga la policy.
    call_tool(compliance_client, "policy_load", arguments={"yaml": SAMPLE_POLICY_YAML})
    # Operator captura snapshot del device cisco (que tiene "Cisco IOS Software" en el buffer mock).
    snap = call_tool(client, "snapshot_create", arguments={
        "sessionId": "session-cisco",
        "snapshotType": "running_config",
        "deviceAlias": "core-router-1",
    })["result"]["structuredContent"]
    assert snap["snapshotId"]

    # Validator evalúa.
    resp = call_tool(validator_client, "policy_evaluate", arguments={
        "policyName": "test-policy",
        "deviceAlias": "core-router-1",
        "markdown": True,
    })
    payload = resp["result"]["structuredContent"]
    assert payload["policyName"] == "test-policy"
    # "Cisco" matchea require → PASS.
    # "telnet" no matchea pattern_deny → PASS.
    assert payload["passCount"] == 2, payload
    assert payload["failCount"] == 0
    assert "## Policy `test-policy`" in payload["markdown"]


def test_evaluate_sin_snapshot_previo_NOT_FOUND(compliance_client, validator_client):
    call_tool(compliance_client, "policy_load", arguments={"yaml": SAMPLE_POLICY_YAML})
    resp = call_tool(validator_client, "policy_evaluate", arguments={
        "policyName": "test-policy",
        "deviceAlias": "alias-fantasma",
    })
    assert resp["result"]["isError"] is True
    msg = resp["result"]["content"][0]["text"]
    assert "snapshot" in msg.lower(), msg


def test_audit_sobre_la_flota(client, compliance_client, validator_client):
    call_tool(compliance_client, "policy_load", arguments={"yaml": SAMPLE_POLICY_YAML})
    # Aseguramos snapshot para core-router-1.
    call_tool(client, "snapshot_create", arguments={
        "sessionId": "session-cisco",
        "snapshotType": "running_config",
        "deviceAlias": "core-router-1",
    })
    # Audit sobre devices del registry. La policy applies_to "cisco_ios" así que solo
    # debería matchear core-router-1 (edge-mk-1 es mikrotik).
    resp = call_tool(validator_client, "policy_audit", arguments={
        "policyName": "test-policy",
        "markdown": False,
    })
    payload = resp["result"]["structuredContent"]
    assert payload["deviceCount"] == 1
    by_device = payload["byDevice"]
    assert by_device[0]["deviceAlias"] == "core-router-1"
    assert by_device[0]["passCount"] == 2


def test_audit_sin_devices_matcheando_conserva_policyName(compliance_client, validator_client):
    # applies_to.device_types=["cisco_ios"] pero filtramos por tag que ningún device tiene
    # → fleet vacía. El payload debe seguir echando el policyName (no string vacío).
    call_tool(compliance_client, "policy_load", arguments={"yaml": SAMPLE_POLICY_YAML})
    resp = call_tool(validator_client, "policy_audit", arguments={
        "policyName": "test-policy",
        "tagsAny": ["__no-existe__"],
        "markdown": True,
    })
    payload = resp["result"]["structuredContent"]
    assert payload["policyName"] == "test-policy", payload
    assert payload["deviceCount"] == 0
    assert payload["byDevice"] == []
    assert "# Audit — policy `test-policy`" in payload["markdown"]


def test_policy_evaluate_no_existente_NOT_FOUND(validator_client):
    resp = call_tool(validator_client, "policy_evaluate", arguments={
        "policyName": "no-existe",
        "deviceAlias": "core-router-1",
    })
    assert resp["result"]["isError"] is True
    msg = resp["result"]["content"][0]["text"]
    assert "no registrada" in msg, msg
