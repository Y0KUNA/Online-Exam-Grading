FROM eclipse-temurin:21-jdk AS build
ARG SERVICE
WORKDIR /workspace
COPY . .
RUN ./gradlew :${SERVICE}:bootJar --no-daemon
FROM eclipse-temurin:21-jre
ARG SERVICE
WORKDIR /app
COPY --from=build /workspace/${SERVICE}/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
