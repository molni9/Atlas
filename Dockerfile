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
    mesa-utils \
    libglu1-mesa \
    wget \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Создание директории для нативных библиотек
RUN mkdir -p /usr/lib/jni

# Скачивание и установка JOGL нативных библиотек
RUN wget https://github.com/WadeWalker/jogl/raw/master/lib/linux-amd64/libgluegen-rt.so -O /usr/lib/jni/libgluegen-rt.so && \
    wget https://github.com/WadeWalker/jogl/raw/master/lib/linux-amd64/libjogl_awt.so -O /usr/lib/jni/libjogl_awt.so && \
    wget https://github.com/WadeWalker/jogl/raw/master/lib/linux-amd64/libjogl_cg.so -O /usr/lib/jni/libjogl_cg.so && \
    wget https://github.com/WadeWalker/jogl/raw/master/lib/linux-amd64/libjogl_desktop.so -O /usr/lib/jni/libjogl_desktop.so && \
    wget https://github.com/WadeWalker/jogl/raw/master/lib/linux-amd64/libnativewindow_awt.so -O /usr/lib/jni/libnativewindow_awt.so && \
    wget https://github.com/WadeWalker/jogl/raw/master/lib/linux-amd64/libnativewindow_x11.so -O /usr/lib/jni/libnativewindow_x11.so && \
    wget https://github.com/WadeWalker/jogl/raw/master/lib/linux-amd64/libnewt.so -O /usr/lib/jni/libnewt.so && \
    chmod 755 /usr/lib/jni/*.so

# Обновление кэша библиотек
RUN ldconfig

# Проверка установленных библиотек
RUN glxinfo | grep "OpenGL vendor" && \
    glxinfo | grep "OpenGL version"

RUN useradd -ms /bin/bash spring-user
USER spring-user
COPY --from=layers /application/dependencies/ ./
COPY --from=layers /application/spring-boot-loader/ ./
COPY --from=layers /application/snapshot-dependencies/ ./
COPY --from=layers /application/application/ ./

# Запуск Xvfb перед приложением
ENTRYPOINT ["sh", "-c", "Xvfb :1 -screen 0 1024x768x24 & export DISPLAY=:1 && java -Djava.awt.headless=true -Djavax.media.opengl.useThreadedGL=true -Djavax.media.opengl.useThreadedGLX=true -Djava.library.path=/usr/lib/jni org.springframework.boot.loader.launch.JarLauncher"]