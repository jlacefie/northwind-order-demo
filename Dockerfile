FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
RUN chmod +x gradlew && ./gradlew --no-daemon installDist -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/install/northwind-order-demo/lib ./lib
