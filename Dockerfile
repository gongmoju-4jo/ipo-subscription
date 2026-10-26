# 모든 서비스 공용 Dockerfile — docker-compose에서 MODULE 빌드 인자로 서비스를 지정한다.
# 실행 전 루트에서 jar를 먼저 빌드해야 한다: ./gradlew build -x test
FROM eclipse-temurin:21-jre
WORKDIR /app
ARG MODULE
COPY ${MODULE}/build/libs/*.jar app.jar
ENV TZ=Asia/Seoul
ENTRYPOINT ["java", "-jar", "app.jar"]
