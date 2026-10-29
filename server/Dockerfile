# syntax=docker/dockerfile:1

FROM gradle:8.10.2-jdk17 AS build
WORKDIR /src

# Small VPS build: keep Gradle and Kotlin in one JVM with a bounded heap.
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=256m -Dkotlin.compiler.execution.strategy=in-process"

COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY src/ src/

RUN chmod +x gradlew \
    && ./gradlew installDist --no-daemon --max-workers=1

FROM eclipse-temurin:17-jre AS runtime

WORKDIR /app
# SQLite lives on a volume mounted at /data (see deploy/docker-compose.yml).
RUN mkdir -p /data

COPY --from=build /src/build/install/family-tracker-server/ /app/

EXPOSE 8080
ENTRYPOINT ["/app/bin/family-tracker-server"]
