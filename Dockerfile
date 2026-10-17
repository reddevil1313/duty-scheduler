# syntax=docker/dockerfile:1

# One image, one process: Spring serves the API and the built client from the
# same origin, as the Vite proxy imitates in development.

# ---- the client -------------------------------------------------------------
FROM node:22-alpine AS web
WORKDIR /web
# The lockfile first, so dependencies are a cached layer until it changes.
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
# Tests are CI's job, not the image's: they start Postgres containers, which
# a build has no Docker to start.
RUN ./gradlew --no-daemon --quiet bootJar \
 && cp "$(ls build/libs/*.jar | grep -v -- '-plain')" app.jar

# ---- what runs ----------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
# The sheet image is drawn with Java2D, which needs fonts to draw any text;
# the slim base has none. And zone data, so TZ can name a zone: the time a
# sheet was logged is printed on its image in the server's zone.
RUN apk add --no-cache fontconfig ttf-dejavu tzdata \
 && fc-cache -f \
 && adduser -S -u 10001 -H duty
COPY --from=api /app/app.jar /app/app.jar
USER duty
EXPOSE 8080
# Up or down only; the details are the owner's (application.yml).
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
