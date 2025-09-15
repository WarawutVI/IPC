# ---- Build stage ----
    FROM gradle:8.10.2-jdk21 AS build
    WORKDIR /app
    COPY . .
    RUN ./gradlew --no-daemon clean shadowJar
    
    # ---- Runtime stage ----
    FROM eclipse-temurin:21-jre
    WORKDIR /app
    COPY --from=build /app/build/libs/*-all.jar /app/app.jar
    ENTRYPOINT ["java","-jar","/app/app.jar","--host","redis","--name","node"]
    