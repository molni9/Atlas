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

# Установка необходимых пакетов для OpenGL
RUN apt-get update && apt-get install -y \
    libgl1-mesa-glx \
    libgl1-mesa-dri \
    xvfb \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -ms /bin/bash spring-user
USER spring-user
COPY --from=layers /application/dependencies/ ./
COPY --from=layers /application/spring-boot-loader/ ./
COPY --from=layers /application/snapshot-dependencies/ ./
COPY --from=layers /application/application/ ./

# Запуск Xvfb перед приложением
ENTRYPOINT ["sh", "-c", "Xvfb :1 -screen 0 1024x768x24 & export DISPLAY=:1 && java org.springframework.boot.loader.launch.JarLauncher"]