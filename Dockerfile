FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /a
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
RUN useradd -r -u 1001 app && mkdir /app && chown app /app
WORKDIR /app
COPY --from=build /a/target/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
