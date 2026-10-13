FROM eclipse-temurin:21-jdk AS build
ARG SERVICE
WORKDIR /workspace
COPY . .
RUN ./gradlew :${SERVICE}:bootJar --no-daemon
FROM eclipse-temurin:21-jre
ARG SERVICE
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/${SERVICE}/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
