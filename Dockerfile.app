# Сборка
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
COPY . .
RUN ./gradlew clean bootJar -x test --no-daemon

# Запуск: Xvfb + X11/OpenGL для JOGL рендеринга
FROM eclipse-temurin:21-jre
WORKDIR /app

# Xvfb и библиотеки для JOGL (X11 + Mesa GL)
RUN apt-get update && apt-get install -y --no-install-recommends xvfb libx11-6 libxext6 libxrandr2 libxrender1 libgl1 libglu1-mesa && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/build/libs/*.jar app.jar
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

EXPOSE 8010
ENTRYPOINT ["/docker-entrypoint.sh"]
