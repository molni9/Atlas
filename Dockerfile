FROM bellsoft/liberica-openjdk-debian:17.0.1 AS builder
WORKDIR /application
COPY . .
RUN --mount=type=cache,target=/root/.gradle  chmod +x gradlew && ./gradlew clean build -x test

FROM bellsoft/liberica-openjre-debian:17.0.1 AS layers
WORKDIR /application
COPY --from=builder /application/build/libs/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM bellsoft/liberica-openjre-debian:17.0.1
VOLUME /tmp
USER root
RUN apt-get update && \
    apt-get install -y xvfb libgl1-mesa-glx libglu1-mesa && \
    rm -rf /var/lib/apt/lists/*
RUN useradd -ms /bin/bash spring-user
USER spring-user
COPY --from=layers /application/dependencies/ ./
COPY --from=layers /application/spring-boot-loader/ ./
COPY --from=layers /application/snapshot-dependencies/ ./
COPY --from=layers /application/application/ ./

ENTRYPOINT ["xvfb-run", "--auto-servernum", "java", "org.springframework.boot.loader.launch.JarLauncher"]
