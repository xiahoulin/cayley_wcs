ARG CAYLEYWCS_BACKEND_PORT=20021

FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:25-jre
ARG CAYLEYWCS_BACKEND_PORT=20021
ENV CAYLEYWCS_BACKEND_PORT=${CAYLEYWCS_BACKEND_PORT}
WORKDIR /app
COPY --from=build /workspace/target/cayleywcs-backend-0.1.0-SNAPSHOT.jar /app/app.jar
EXPOSE ${CAYLEYWCS_BACKEND_PORT}
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
