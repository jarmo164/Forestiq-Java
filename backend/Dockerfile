FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN ./gradlew --no-daemon dependencies >/dev/null
COPY src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-alpine
ARG FORESTIQ_RELEASE_ID=dev
ARG FORESTIQ_COMMIT_SHA=unknown
ENV FORESTIQ_RELEASE_ID=$FORESTIQ_RELEASE_ID \
    FORESTIQ_COMMIT_SHA=$FORESTIQ_COMMIT_SHA
WORKDIR /app
RUN apk add --no-cache curl && addgroup -S forestiq && adduser -S forestiq -G forestiq
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 755 /app/docker-entrypoint.sh
USER forestiq
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["/app/docker-entrypoint.sh"]
