#!/bin/bash
set -e

CONTAINER_NAME="node-exporter"
IMAGE="prom/node-exporter:latest"

echo "Pulling image: $IMAGE"
docker pull "$IMAGE"

if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
  echo "Removing existing container: $CONTAINER_NAME"
  docker rm -f "$CONTAINER_NAME"
fi

echo "Starting container: $CONTAINER_NAME"
docker run -d \
  --name "$CONTAINER_NAME" \
  --restart=always \
  --network host \
  --pid host \
  "$IMAGE"

echo "Done. Container $CONTAINER_NAME is running."
