#!/bin/bash
set -euo pipefail

SERVER="${MCP_SERVER:-http://localhost:8099}"
MCP_URL="$SERVER/mcp"
PROTOCOL_VERSION="2025-11-25"
HEADERS=$(mktemp /tmp/mcp-headers-XXXX)
SESSION_ID=""

cleanup() {
  if [ -n "$SESSION_ID" ]; then
    curl -s -o /dev/null -X DELETE "$MCP_URL" -H "Mcp-Session-Id: $SESSION_ID" || true
  fi
  rm -f "$HEADERS"
}
trap cleanup EXIT

# ── Hilfsfunktionen (siehe curl-test.sh) ──────────────────────────────────
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
result = json.load(sys.stdin).get('result', {})
data = result.get('structuredContent')
if data is None:
    texts = [c['text'] for c in result.get('content', []) if c.get('type') == 'text']
    if not texts:
        sys.exit(0)
    try:
        data = json.loads(texts[0])
    except Exception:
        print('  => ' + texts[0])
        sys.exit(0)
processes = data.get('processes') if isinstance(data, dict) else None
if not isinstance(processes, list):
    print('  => ' + json.dumps(data))
    sys.exit(0)
col_pid  = max(5, max((len(str(p['pid']))  for p in processes), default=5))
col_name = max(4, max((len(p['name'])      for p in processes), default=4))
col_cpu  = 6
col_ram  = 8
sep = '-' * (col_pid + col_name + col_cpu + col_ram + 13)
fmt = '  {:<{}} | {:<{}} | {:>{}} | {:>{}}'
print('')
print(fmt.format('PID', col_pid, 'Name', col_name, 'CPU %', col_cpu, 'RAM (MB)', col_ram))
print('  ' + sep)
for p in processes:
    print(fmt.format(
        p['pid'],    col_pid,
        p['name'],   col_name,
        p['cpuPercent'], col_cpu,
        p['ramMb'],  col_ram,
    ))
" 2>/dev/null || true)
    [ -n "$extra" ] && echo "$extra"
  else
    echo "(keine Antwort)"
  fi
  echo ""
}

# ── Handshake ─────────────────────────────────────────────────────────────
echo "Connecting to $MCP_URL ..."
send_request "initialize" '{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "'"$PROTOCOL_VERSION"'",
    "capabilities": {},
    "clientInfo": { "name": "curl-java-test", "version": "1.0" }
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

# ── listJavaProcesses aufrufen ────────────────────────────────────────────
send_request "tools/call listJavaProcesses" '{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "listJavaProcesses",
    "arguments": {}
  }
}'
