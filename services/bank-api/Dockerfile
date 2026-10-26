# syntax=docker/dockerfile:1
# Build context is the repository root (it needs frontend/ too):
#   docker build -f services/bank-api/Dockerfile .

# 1) React frontend -> static files
FROM node:26-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# 2) Spring Boot jar with the frontend bundled under /static
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /build
COPY services/bank-api/pom.xml ./
RUN mvn -B -q -DskipFrontend dependency:go-offline
COPY services/bank-api/src ./src
COPY --from=frontend /frontend/dist ./src/main/resources/static
RUN mvn -B -q -DskipFrontend -DskipTests package

# 3) Runtime
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 mockbank
WORKDIR /app
COPY --from=backend /build/target/bank-api-0.0.1-SNAPSHOT.jar app.jar
USER mockbank
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=10 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
