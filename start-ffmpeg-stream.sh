#!/bin/bash

# Скрипт для запуска FFmpeg, который берёт MJPEG от Spring Boot
# и пушит его в Janus как RTP/H.264 поток

OBJECT_KEY="${1:-височная%20кость1.obj}"
MJPEG_URL="http://localhost:8010/stream/${OBJECT_KEY}/mjpeg"
JANUS_RTP_HOST="${JANUS_RTP_HOST:-localhost}"
JANUS_RTP_PORT="${JANUS_RTP_PORT:-5004}"

echo "Starting FFmpeg stream:"
echo "  MJPEG source: ${MJPEG_URL}"
echo "  RTP destination: ${JANUS_RTP_HOST}:${JANUS_RTP_PORT}"

# Проверяем наличие ffmpeg
if ! command -v ffmpeg &> /dev/null; then
    echo "ERROR: ffmpeg not found. Please install ffmpeg first."
    echo "  macOS: brew install ffmpeg"
    echo "  Or download from: https://evermeet.cx/ffmpeg/"
    exit 1
fi

# Запускаем FFmpeg
ffmpeg -re \
    -f mjpeg \
    -i "${MJPEG_URL}" \
    -c:v libx264 \
    -preset veryfast \
    -tune zerolatency \
    -profile:v baseline \
    -level 3.0 \
    -pix_fmt yuv420p \
    -r 30 \
    -g 30 \
    -keyint_min 30 \
    -b:v 2M \
    -maxrate 2M \
    -bufsize 4M \
    -f rtp \
    "rtp://${JANUS_RTP_HOST}:${JANUS_RTP_PORT}?pkt_size=1200"

