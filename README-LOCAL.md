# Локальный запуск WebRTC (на хосте)

## Быстрый старт

### 1. Запусти Janus локально

```bash
./start-janus-local.sh
```

Это запустит Janus через Docker на твоём хосте (localhost).

### 2. Запусти Spring Boot

```bash
./gradlew bootRun
```

### 3. Запусти FFmpeg стрим (в отдельном терминале)

```bash
./start-ffmpeg-stream.sh "височная%20кость1.obj"
```

### 4. Открой браузер

```
http://localhost:8010/webrtc.html
```

## Проверка

### Проверить, что Janus работает:

```bash
curl http://localhost:8088/janus/info
```

Должен вернуть JSON с информацией о Janus.

### Посмотреть логи Janus:

```bash
docker logs janus -f
```

### Остановить Janus:

```bash
docker-compose stop janus
```

## Если Docker не установлен на хосте

Если у тебя Docker только в виртуальной машине, можно:

1. **Установить Docker Desktop для macOS** (если возможно)
2. **Или использовать IP виртуальной машины** в конфигурации

Для варианта 2:
- В `webrtc.html` замени `localhost` на IP виртуальной машины
- В `start-ffmpeg-stream.sh` задай `JANUS_RTP_HOST=<IP_ВМ>`

## Troubleshooting

### Janus не запускается
```bash
docker logs janus
```

### Порт занят
Если порт 8188 занят, измени в `docker-compose.yaml`:
```yaml
ports:
  - "8189:8188"  # вместо 8188:8188
```

И обнови `webrtc.html`:
```javascript
const JANUS_URL = 'ws://localhost:8189/janus';
```

### FFmpeg не найден
Установи:
```bash
brew install ffmpeg
```

Или скачай готовый бинарник с https://evermeet.cx/ffmpeg/

