# syntax=docker/dockerfile:1
# LoadMasterService: build bằng Maven wrapper (JDK 21) rồi chạy jar trên JRE 21, user không phải root.
# Build:  docker build -t loadmaster-service ./LoadMasterService

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests package \
    && cp target/LoadMasterService-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre
# Ứng dụng tự đặt múi giờ Asia/Ho_Chi_Minh; TZ giữ log của JVM cùng giờ
ENV TZ=Asia/Ho_Chi_Minh \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
RUN groupadd --system loadmaster && useradd --system --gid loadmaster --home-dir /app --no-create-home loadmaster
WORKDIR /app
COPY --from=build --chown=loadmaster:loadmaster /workspace/app.jar app.jar
USER loadmaster
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
