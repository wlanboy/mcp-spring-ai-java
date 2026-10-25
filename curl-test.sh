#!/bin/bash
set -euo pipefail

SERVER="${MCP_SERVER:-http://localhost:8099}"
MCP_URL="$SERVER/mcp"
PROTOCOL_VERSION="2025-11-25"
HEADERS=$(mktemp /tmp/mcp-headers-XXXX)
SESSION_ID=""

cleanup() {
  # Session serverseitig beenden (Streamable HTTP: DELETE mit Session-Header)
  if [ -n "$SESSION_ID" ]; then
    curl -s -o /dev/null -X DELETE "$MCP_URL" -H "Mcp-Session-Id: $SESSION_ID" || true
  fi
  rm -f "$HEADERS"
}
trap cleanup EXIT

# ── Hilfsfunktionen ────────────────────────────────────────────────────────

# Sendet eine JSON-RPC-Nachricht per POST an den einzigen Streamable-HTTP-Endpoint /mcp.
# Ab dem initialize-Request wird die vom Server vergebene Session-ID mitgeschickt.
mcp_post() {
  local body="$1"
  local args=(-s -X POST "$MCP_URL"
    -H "Content-Type: application/json"
    -H "Accept: application/json, text/event-stream"
    -H "MCP-Protocol-Version: $PROTOCOL_VERSION"
    -D "$HEADERS")
  if [ -n "$SESSION_ID" ]; then
    args+=(-H "Mcp-Session-Id: $SESSION_ID")
  fi
  curl "${args[@]}" -d "$body"
}

# Der Server antwortet entweder mit application/json oder mit einem SSE-Stream
# ("data: {...}"-Zeilen, z.B. wenn vor dem Ergebnis Progress-/Log-Notifications kommen).
# Gibt in beiden Fällen die JSON-RPC-Antwort mit der passenden id aus.
extract_response() {
  python3 -c '
import sys, json
want = int(sys.argv[1])
raw = sys.stdin.read()
messages = [l[5:].strip() for l in raw.splitlines() if l.startswith("data:")] or [raw]
for m in messages:
    try:
        r = json.loads(m)
    except ValueError:
        continue
    if r.get("id") == want:
        print(json.dumps(r))
        break
' "$1"
}

send_request() {
  local label="$1"
  local body="$2"
  local id
  id=$(echo "$body" | grep -o '"id": *[0-9]\+' | grep -o '[0-9]\+')

  local response
  response=$(mcp_post "$body" | extract_response "$id" || true)

  echo "[$label]"
  if [ -n "$response" ]; then
    echo "$response" | python3 -m json.tool 2>/dev/null || echo "$response"
    local extra
    extra=$(echo "$response" | python3 -c "
import sys, json
r = json.load(sys.stdin)
result = r.get('result', {})
# tools/list: Namen, Titel + Beschreibung auflisten
tools = result.get('tools', [])
if tools:
    print('  Verfügbare Tools:')
    for t in tools:
        schema = ' [outputSchema]' if t.get('outputSchema') else ''
        print('    - ' + t['name'] + ' (' + t.get('title', '') + ')' + schema + ': ' + t.get('description', ''))
# tools/call: Rückgabewert ausgeben (strukturiert, falls vorhanden)
if 'structuredContent' in result:
    print('  => ' + json.dumps(result['structuredContent']))
else:
    texts = [c['text'] for c in result.get('content', []) if c.get('type') == 'text']
    if texts: print('  => ' + ' | '.join(texts))
" 2>/dev/null || true)
    [ -n "$extra" ] && echo "$extra"
  else
    echo "(keine Antwort)"
  fi
  echo ""
}

# ── 1. MCP Handshake ───────────────────────────────────────────────────────
echo "Connecting to $MCP_URL ..."
send_request "initialize" '{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "'"$PROTOCOL_VERSION"'",
    "capabilities": {},
    "clientInfo": { "name": "curl-test", "version": "1.0" }
  }
}'

SESSION_ID=$(grep -i '^mcp-session-id:' "$HEADERS" | cut -d' ' -f2 | tr -d '\r' || true)
if [ -z "$SESSION_ID" ]; then
  echo "ERROR: Keine Mcp-Session-Id im initialize-Response erhalten."
  cat "$HEADERS"
  exit 1
fi
echo "Session: $SESSION_ID"
echo ""

mcp_post '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null
echo "[notifications/initialized] sent"
echo ""

# ── 2. Tools auflisten ─────────────────────────────────────────────────────
send_request "tools/list" '{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list"
}'

# ── 3. greet aufrufen ─────────────────────────────────────────────────────
send_request "tools/call greet" '{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "greet",
    "arguments": { "name": "World" }
  }
}'

# ── 4. serverTime aufrufen ────────────────────────────────────────────────
send_request "tools/call serverTime" '{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "serverTime",
    "arguments": {}
  }
}'
