#!/bin/bash
set -euo pipefail

SERVER="${MCP_SERVER:-http://localhost:8080}"
SSE_LOG=$(mktemp /tmp/mcp-sse-XXXX.log)

cleanup() {
  kill "$SSE_PID" 2>/dev/null || true
  rm -f "$SSE_LOG"
}
trap cleanup EXIT

# ── SSE-Verbindung aufbauen ────────────────────────────────────────────────
echo "Connecting to $SERVER/sse ..."
curl -sN "$SERVER/sse" >> "$SSE_LOG" &
SSE_PID=$!

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

# ── Hilfsfunktion ─────────────────────────────────────────────────────────
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
contents = r.get('result', {}).get('content', [])
texts = [c['text'] for c in contents if c.get('type') == 'text']
if not texts:
    sys.exit(0)
try:
    processes = json.loads(texts[0])
except Exception:
    print('  => ' + texts[0])
    sys.exit(0)
if not isinstance(processes, list):
    print('  => ' + texts[0])
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
      echo "(keine Antwort innerhalb von 3 s)"
    fi
  else
    echo "[$label] notification sent"
  fi
  echo ""
}

# ── Handshake ─────────────────────────────────────────────────────────────
send_request "initialize" '{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": { "name": "curl-java-test", "version": "1.0" }
  }
}'

curl -sf -X POST "$SERVER$ENDPOINT" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null
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
