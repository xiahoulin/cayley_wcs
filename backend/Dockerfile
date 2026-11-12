ARG CAYLEYWCS_BACKEND_PORT=20021

FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# Pin the JVM version and content digest so cached images cannot change
# the runtime selected for a deployment.
FROM eclipse-temurin:25.0.5_8-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0
ARG CAYLEYWCS_BACKEND_PORT=20021
ENV CAYLEYWCS_BACKEND_PORT=${CAYLEYWCS_BACKEND_PORT}
WORKDIR /app
COPY --from=build /workspace/target/cayleywcs-backend-0.1.0-SNAPSHOT.jar /app/app.jar
EXPOSE ${CAYLEYWCS_BACKEND_PORT}
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
