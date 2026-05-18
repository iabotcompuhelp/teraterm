# OpenTermX MCP — user guide

> **One page**: OpenTermX can act as an MCP server. That means your favorite AI client
> (Claude Desktop, Cursor, Cline, Continue) can list the SSH/Telnet/Serial sessions you
> have open, query your RAG knowledge base, and propose commands to inject into a network
> device — but **you must approve them every time** before anything executes. The AI
> suggests; you decide.

## Connect Claude Desktop in 5 minutes

1. Open OpenTermX.
2. `Setup → AI Assistant…`, **MCP Server** tab.
3. Tick *Enable MCP server*. Click *Generate* to create a token.
4. In the snippet dropdown, choose **CLAUDE_DESKTOP**. Click *Copy snippet*.
5. Click *Open folder* — opens the directory containing `claude_desktop_config.json`.
6. Edit that file and paste the snippet (merge with anything already in `mcpServers`).
   ![screenshot: claude_desktop_config_paste]
7. Close and reopen Claude Desktop. In a new conversation, you'll see a 🔌 icon meaning
   `opentermx` is connected.
   ![screenshot: claude_desktop_connected]
8. Try: ask "List my OpenTermX sessions". It should invoke `list_sessions`.

## Connect Cursor in 5 minutes

1. Steps 1-4 as above, but select **CURSOR** in the snippet dropdown.
2. *Open folder* opens `~/.cursor/`. Edit `mcp.json` (create if missing) and paste the snippet.
   ![screenshot: cursor_mcp_config]
3. Reload window. The MCP panel on the right shows the 10 OpenTermX tools.
4. Try: "Use inspect_session against session-cisco and summarize recent errors".

## Connect Cline (VS Code) in 5 minutes

1. Steps 1-4 with **CLINE** selected.
2. *Open folder* opens Cline's `globalStorage` directory. Paste the snippet in
   `cline_mcp_settings.json`.
   ![screenshot: cline_settings]
3. In Cline's panel, click MCP Servers → `opentermx` should appear with a green icon.
4. Try: "Inspect my active sessions".

## Troubleshooting

### "Claude Desktop says failed to connect"

- Confirm the MCP server is running: OpenTermX status bar should show
  `🧠 MCP :127.0.0.1:8765` (green).
- If it shows `MCP: failed (click for details)`, click on it — it'll show the reason
  (port in use, permission denied, etc).
- Try `curl http://127.0.0.1:8765/mcp/health` from a terminal — should return `RUNNING`.
- If you set a token, ensure the snippet's `Authorization: Bearer X` exactly matches yours.

### "Port in use"

- Some other app is using 8765. Change it in *Setup → AI Assistant → MCP Server → Port*,
  e.g. 18765. Regenerate the snippet and update your client.

### "The LLM says there are no tools"

- Verify the client is connected: in Claude Desktop, check
  `~/Library/Logs/Claude/mcp*.log` for `opentermx` entries.
- Remember: **you must restart the client** after editing its config.
- If you just started OpenTermX, give the client ~5s to reconnect.

### "The approval dialog doesn't appear"

- `propose_commands`, `run_macro`, `open_session`, `close_session` ALWAYS open the
  approval dialog. If you don't see it:
  - Check if OpenTermX is minimized — the modal might be hidden.
  - Verify you didn't tick *Read-only mode* — that makes these tools return an error
    without ever opening the dialog.
- In MCP Server settings, enable *Verbose log* and retry: OpenTermX logs
  (`~/.opentermx/logs/opentermx.log`) will show the raw request and what happened.

### "The LLM sees credentials in my configs"

It shouldn't: **credential redaction** is always active. If you find a pattern we miss
(some vendor with unusual `password` syntax), add it under *Setup → AI Assistant →
MCP Server → Custom redaction rules* as `(regex, replacement)`.

## Security — one page

- **Default bind `127.0.0.1`**: only local connections can talk to the server. If you
  need remote access, tick *Enable TLS* and set up the keystore. NEVER bind to
  `0.0.0.0` without TLS — the bearer token would travel in plaintext.
- **Hashed tokens**: in `settings.json` we only store the SHA-256 hash of each token, never
  the plaintext. A leaked `settings.json` gives an attacker hashes, not tokens.
- **Audit log**: every `propose_commands` call is logged in `~/.opentermx/audit-ia.csv`
  with timestamp, session, vendor, commands, operator decision, and output. Append-only.
- **Rate limit + circuit breaker**: if a client tries to abuse (60+ requests/min, or
  repeated `propose_commands` rejections), the server returns 429 and bans the token
  for 10 minutes.
- **Per-session ACL**: optional. If you set `Allowed sessions (glob) = lab-*`, tools
  reject any attempt to touch sessions that don't match the glob.

## Useful commands

```bash
# Post-install smoke test: runs each tool with timeouts
python tools/mcp/smoke_test.py --url http://127.0.0.1:8765 --token YOUR_TOKEN

# Schema diff between versions (useful before release)
python tools/mcp/schema_diff.py before.json after.json
```

## Troubleshooting

Recurring findings from the T1 pre-flight (manual testing). If you hit any of these, check here before opening an issue.

### PowerShell shows accented characters as `Ã³` or `Ã­`

**Cause:** the console isn't UTF-8 by default; PowerShell reads the UTF-8 bytes returned by the server as Latin-1, so `ó` appears as `Ã³` and `í` as `Ã­`.

**One-shot fix** (current session only):

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
```

**Persistent fix:** add it to your `$PROFILE`:

```powershell
notepad $PROFILE
# Paste the two lines above and save.
```

Reopen PowerShell and it should be fixed for `curl`, `Invoke-RestMethod`, etc.

### Curl in PowerShell fails with JSON parse errors

**Cause:** PowerShell does **not** interpret `\"` as an escape — it preserves the backslash literally. So `curl -d "{\"foo\":1}"` actually sends `{\"foo\":1}` to the server, and JSON parsing fails with `Unexpected character`.

**Recommended fix** — use `Invoke-RestMethod` (PowerShell-native, handles JSON cleanly):

```powershell
$body = @{
    jsonrpc = "2.0"
    id      = 1
    method  = "initialize"
    params  = @{ protocolVersion = "2024-11-05" }
} | ConvertTo-Json -Compress

Invoke-RestMethod -Uri "http://127.0.0.1:8765/mcp" `
    -Method Post `
    -Headers @{ "Authorization" = "Bearer YOUR_TOKEN" } `
    -ContentType "application/json" `
    -Body $body
```

**Alternative** if you really need `curl.exe`: use the stop-parsing token `--%`:

```powershell
curl.exe --% -X POST http://127.0.0.1:8765/mcp -H "Authorization: Bearer YOUR_TOKEN" -H "Content-Type: application/json" -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"
```

`--%` tells PowerShell "stop parsing, pass everything after this literally".

### MCP client returns `-32600` "Client not initialized"

**Cause:** the MCP spec requires `initialize` as the first request on a session. Real clients (Claude Desktop, Cursor, Cline) do this automatically; when testing manually with curl, you have to do it yourself.

**Fix** — the two-step dance:

```powershell
# 1) Initialize and capture the negotiated version
$init = Invoke-RestMethod -Uri "http://127.0.0.1:8765/mcp" -Method Post `
    -Headers @{ "Authorization" = "Bearer YOUR_TOKEN" } `
    -ContentType "application/json" `
    -Body (@{ jsonrpc="2.0"; id=1; method="initialize"; params=@{ protocolVersion="2024-11-05" } } | ConvertTo-Json -Compress)

$version = $init.result.protocolVersion

# 2) Call the tool with the MCP-Protocol-Version header
Invoke-RestMethod -Uri "http://127.0.0.1:8765/mcp" -Method Post `
    -Headers @{ "Authorization" = "Bearer YOUR_TOKEN"; "MCP-Protocol-Version" = $version } `
    -ContentType "application/json" `
    -Body (@{ jsonrpc="2.0"; id=2; method="tools/list" } | ConvertTo-Json -Compress)
```

If the second request omits the `MCP-Protocol-Version` header, the server returns `-32600 Missing MCP-Protocol-Version header`.

### MCP client doesn't show up in Claude Desktop after configuring

**Most common cause:** Claude Desktop is still running in the background after you closed the window — the new config isn't loaded until the process itself dies.

**Fix:**

- **macOS:** `Cmd+Q` on the app (closing the window doesn't quit it), or `killall "Claude"` in the terminal.
- **Windows:** right-click the tray icon → "Quit", or `taskkill /F /IM Claude.exe`.

Reopen and the MCP server should appear in the list.

**Secondary cause:** invalid JSON in `claude_desktop_config.json`. Validate it:

```bash
jq . ~/Library/Application\ Support/Claude/claude_desktop_config.json   # macOS
jq . $env:APPDATA\Claude\claude_desktop_config.json                      # Windows (PowerShell)
```

If `jq` fails, the JSON is broken — extra comma, unclosed quote, etc. Fix it and restart Claude Desktop.