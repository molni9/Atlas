FROM bellsoft/liberica-openjdk-debian:21 AS builder
WORKDIR /application
COPY . .
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew clean build -x test

FROM bellsoft/liberica-openjre-debian:21 AS layers
WORKDIR /application
COPY --from=builder /application/build/libs/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM bellsoft/liberica-openjre-debian:21
VOLUME /tmp

# Установка необходимых OpenGL и X11 библиотек + виртуальный дисплей Xvfb
RUN apt-get update && apt-get install -y \
    libgl1-mesa-glx \
    libgl1-mesa-dri \
    libglu1-mesa \
    libx11-6 \
    libxext6 \
    libxrender1 \
    libxtst6 \
    libxi6 \
    xvfb \
    fontconfig \
    libharfbuzz0b \
    && rm -rf /var/lib/apt/lists/*

# Копирование слоев приложения
COPY --from=layers /application/dependencies/ ./
COPY --from=layers /application/spring-boot-loader/ ./
COPY --from=layers /application/snapshot-dependencies/ ./
COPY --from=layers /application/application/ ./

# Запуск: поднимаем Xvfb на свободном DISPLAY, затем стартуем приложение.
# Важно: иногда остаётся /tmp/.X99-lock (после некорректного завершения) — поэтому выбираем первый свободный :99..:109
ENTRYPOINT ["bash", "-c", "for d in $(seq 99 109); do if [ -e /tmp/.X${d}-lock ]; then continue; fi; Xvfb :${d} -screen 0 1280x720x24 -ac +extension GLX +render -noreset >/tmp/xvfb-${d}.log 2>&1 & sleep 0.3; if kill -0 $! >/dev/null 2>&1; then export DISPLAY=:${d}; echo \"Xvfb started on $DISPLAY\"; break; fi; done; if [ -z \"$DISPLAY\" ]; then echo \"Failed to start Xvfb\"; ls -la /tmp/.X*-lock 2>/dev/null || true; exit 1; fi; JAVA_BIN=\"$(command -v java 2>/dev/null || true)\"; if [ -z \"$JAVA_BIN\" ]; then for p in /usr/bin/java /bin/java /opt/java/openjdk/bin/java /usr/lib/jvm/*/bin/java /usr/lib/jvm/*/*/bin/java; do if [ -x \"$p\" ]; then JAVA_BIN=\"$p\"; break; fi; done; fi; if [ -z \"$JAVA_BIN\" ] || [ ! -x \"$JAVA_BIN\" ]; then echo \"Java not found in image\"; echo \"PATH=$PATH\"; ls -la /usr/bin/java /opt/java/openjdk/bin/java /usr/lib/jvm/*/bin/java /usr/lib/jvm/*/*/bin/java 2>/dev/null || true; exit 1; fi; echo \"Using Java: $JAVA_BIN\"; exec \"$JAVA_BIN\" -Djava.awt.headless=true org.springframework.boot.loader.launch.JarLauncher"]