FROM node:20-bookworm AS frontend-build
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install --include=dev
COPY frontend/ ./
RUN npm run build:docker

FROM gradle:8.5-jdk17 AS backend-build
WORKDIR /app
COPY backend/ ./backend/
RUN cd backend && gradle fatJar -x test

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends iperf3 iputils-ping && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=backend-build /app/backend/build/libs/ /app/backend/libs/
COPY --from=frontend-build /app/frontend/dist/ /app/frontend/dist/
COPY config.json /app/config.json
EXPOSE 8080
CMD ["java", "-jar", "/app/backend/libs/network_observer_backend.jar", "--config", "/app/config.json", "--frontend-dir", "/app/frontend/dist", "--host", "0.0.0.0", "--port", "8080"]
