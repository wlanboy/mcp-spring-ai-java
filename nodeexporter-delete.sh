#!/bin/bash
set -e

CONTAINER_NAME="node-exporter"

if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
  echo "Removing container: $CONTAINER_NAME"
  docker rm -f "$CONTAINER_NAME"
  echo "Done. Container $CONTAINER_NAME has been removed."
else
  echo "Container $CONTAINER_NAME does not exist. Nothing to do."
fi
