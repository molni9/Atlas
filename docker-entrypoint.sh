#!/bin/sh
set -e
# Виртуальный дисплей для JOGL/OpenGL в контейнере
Xvfb :99 -screen 0 1920x1440x24 -ac &
export DISPLAY=:99
# Даём Xvfb пару секунд подняться
sleep 2
exec java -Djava.awt.headless=true -jar /app/app.jar
