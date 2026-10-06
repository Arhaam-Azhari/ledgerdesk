FROM node:22-bookworm-slim AS frontend
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
COPY docs/examples/ /build/docs/examples/
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /build/backend
COPY backend/pom.xml ./
COPY backend/src/ ./src/
RUN mvn -B package -DskipTests

FROM eclipse-temurin:17-jre-jammy AS api
RUN groupadd --gid 10001 ledgerdesk && useradd --uid 10001 --gid 10001 --no-create-home ledgerdesk
WORKDIR /app
COPY --from=backend /build/backend/target/ledgerdesk-0.1.0.jar ./ledgerdesk.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/ledgerdesk.jar"]

FROM caddy:2-alpine AS web
COPY --from=frontend /build/frontend/dist/ /srv/
COPY deploy/Caddyfile /etc/caddy/Caddyfile
