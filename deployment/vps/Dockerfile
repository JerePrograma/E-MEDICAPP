FROM node:24-bookworm-slim AS web
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
ARG PUBLIC_ORIGIN=https://srv2083548.hstgr.cloud
ENV VITE_BASE_PATH=/ VITE_API_BASE_URL=${PUBLIC_ORIGIN}
RUN VITE_API_BASE_URL= npm test && npm run build

FROM maven:3.9-eclipse-temurin-25 AS backend
WORKDIR /build
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
COPY --from=web /build/frontend/dist backend/src/main/resources/static
ARG BUILD_COMMIT=unknown
RUN printf '{"commit":"%s","backendUrl":"https://srv2083548.hstgr.cloud"}' "$BUILD_COMMIT" > backend/src/main/resources/static/deployment.json
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -f backend/pom.xml package

FROM eclipse-temurin:25-jre
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=backend /build/backend/target/emedicapp-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
