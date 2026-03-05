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
    && rm -rf /var/lib/apt/lists/*

# Создание пользователя
RUN useradd -ms /bin/bash spring-user
USER spring-user

# Копирование слоев приложения
COPY --from=layers /application/dependencies/ ./
COPY --from=layers /application/spring-boot-loader/ ./
COPY --from=layers /application/snapshot-dependencies/ ./
COPY --from=layers /application/application/ ./

# Запуск приложения под виртуальным X-сервером (headless рендер вместо заглушки)
ENTRYPOINT ["bash", "-c", "xvfb-run -s '-screen 0 1280x720x24' java org.springframework.boot.loader.launch.JarLauncher"]