# syntax=docker/dockerfile:1

# Spring serves both the API and the built client.

# ---- the client -------------------------------------------------------------
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ---- the API, with the client inside it ------------------------------------------
FROM eclipse-temurin:21-jdk AS api
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon --quiet dependencies > /dev/null
COPY src src
COPY --from=web /web/dist src/main/resources/static
# Tests run in CI (they need Docker).
RUN ./gradlew --no-daemon --quiet bootJar \
 && cp "$(ls build/libs/*.jar | grep -v -- '-plain')" app.jar

# ---- what runs ----------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
# Fonts for the Java2D sheet image, and tzdata for TZ.
RUN apk add --no-cache fontconfig ttf-dejavu tzdata \
 && fc-cache -f \
 && adduser -S -u 10001 -H duty
COPY --from=api /app/app.jar /app/app.jar
USER duty
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
