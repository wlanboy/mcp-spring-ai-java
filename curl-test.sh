#!/bin/bash
set -euo pipefail

SERVER="${MCP_SERVER:-http://localhost:8080}"
SSE_LOG=$(mktemp /tmp/mcp-sse-XXXX.log)

cleanup() {
  kill "$SSE_PID" 2>/dev/null || true
  rm -f "$SSE_LOG"
}
trap cleanup EXIT

# ── 1. SSE-Verbindung aufbauen ─────────────────────────────────────────────
echo "Connecting to $SERVER/sse ..."
curl -sN "$SERVER/sse" >> "$SSE_LOG" &
SSE_PID=$!

# Warten bis der Server den Session-Endpoint liefert
ENDPOINT=""
for _ in $(seq 20); do
  sleep 0.3
  ENDPOINT=$(grep -o '/mcp/message[^"[:space:]]*' "$SSE_LOG" 2>/dev/null | head -1 || true)
  [ -n "$ENDPOINT" ] && break
done

if [ -z "$ENDPOINT" ]; then
  echo "ERROR: Kein Session-Endpoint im SSE-Stream empfangen."
  echo "--- SSE log ---"
  cat "$SSE_LOG"
  exit 1
fi
echo "Session endpoint: $ENDPOINT"
echo ""

# ── Hilfsfunktionen ────────────────────────────────────────────────────────

# Sendet eine JSON-RPC-Nachricht und wartet auf die Antwort im SSE-Stream.
# Antworten kommen als "data: {...}" Zeilen im SSE-Stream zurück.
send_request() {
  local label="$1"
  local body="$2"
  local id
  id=$(echo "$body" | grep -o '"id": *[0-9]\+' | grep -o '[0-9]\+' || echo "")

  local lines_before
  lines_before=$(wc -l < "$SSE_LOG")

  curl -sf -X POST "$SERVER$ENDPOINT" \
    -H "Content-Type: application/json" \
    -d "$body" > /dev/null

  # Auf Antwort im SSE-Stream warten (max 3 s)
  if [ -n "$id" ]; then
    local response=""
    for _ in $(seq 30); do
      sleep 0.1
      response=$(tail -n +"$lines_before" "$SSE_LOG" \
        | grep '^data:' \
        | grep "\"id\":$id" \
        | head -1 \
        | sed 's/^data: *//' || true)
      [ -n "$response" ] && break
    done
    echo "[$label]"
    if [ -n "$response" ]; then
      echo "$response" | python3 -m json.tool 2>/dev/null || echo "$response"
      local extra
      extra=$(echo "$response" | python3 -c "
import sys, json
r = json.load(sys.stdin)
result = r.get('result', {})
# tools/list: Namen + Beschreibung auflisten
tools = result.get('tools', [])
if tools:
    print('  Verfügbare Tools:')
    for t in tools:
        print('    - ' + t['name'] + ': ' + t.get('description', ''))
# tools/call: Rückgabewert ausgeben
contents = result.get('content', [])
texts = [c['text'] for c in contents if c.get('type') == 'text']
if texts: print('  => ' + ' | '.join(texts))
" 2>/dev/null || true)
      [ -n "$extra" ] && echo "$extra"
    else
      echo "(keine Antwort innerhalb von 3 s)"
    fi
  else
    echo "[$label] notification sent"
  fi
  echo ""
}

# ── 2. MCP Handshake ───────────────────────────────────────────────────────
send_request "initialize" '{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": { "name": "curl-test", "version": "1.0" }
  }
}'

curl -sf -X POST "$SERVER$ENDPOINT" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null
echo "[notifications/initialized] sent"
echo ""

# ── 3. Tools auflisten ─────────────────────────────────────────────────────
send_request "tools/list" '{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list"
}'

# ── 4. greet aufrufen ─────────────────────────────────────────────────────
send_request "tools/call greet" '{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "greet",
    "arguments": { "name": "World" }
  }
}'

# ── 5. serverTime aufrufen ────────────────────────────────────────────────
send_request "tools/call serverTime" '{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "serverTime",
    "arguments": {}
  }
}'
