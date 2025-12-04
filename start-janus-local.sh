#!/bin/bash

# Скрипт для запуска Janus локально через Docker на хосте

echo "Starting Janus Gateway locally..."

# Проверяем, запущен ли уже Janus
if docker ps | grep -q janus; then
    echo "Janus is already running"
    docker ps | grep janus
    exit 0
fi

# Запускаем Janus
docker-compose up -d janus

# Ждём запуска
echo "Waiting for Janus to start..."
sleep 5

# Проверяем статус
if docker ps | grep -q janus; then
    echo "✓ Janus started successfully"
    echo ""
    echo "Janus WebSocket: ws://localhost:8188/janus"
    echo "Janus HTTP API: http://localhost:8088"
    echo ""
    echo "To check logs: docker logs janus"
    echo "To stop: docker-compose stop janus"
else
    echo "✗ Failed to start Janus"
    echo "Check logs: docker logs janus"
    exit 1
fi

