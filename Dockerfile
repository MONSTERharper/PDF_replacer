FROM node:22-bookworm-slim AS frontend
WORKDIR /build
COPY pom.xml .
COPY src ./src
COPY new-ui/package.json new-ui/package-lock.json ./new-ui/
RUN cd new-ui && npm ci
COPY new-ui/ ./new-ui/
RUN cd new-ui && npm run build

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY --from=frontend /build/src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core fonts-noto-core \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]