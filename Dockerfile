# Runtime image only: the jar is built on the host with ./mvnw package (bench/run-all.sh does it).
# Building inside Docker would re-download all Maven dependencies on every image build.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY target/reservation-engine-*.jar app.jar

# Fixed heap so the three benchmark instances cannot grow and starve PostgreSQL/k6 of memory.
# ExitOnOutOfMemoryError: after an OOM the JVM is in an unknown state (in stage 7a an OOM killed
# Tomcat's acceptor thread: process alive, no HTTP served, ever). Exit instead, so the container
# restart policy brings up a clean instance. Fail fast, also for the process itself.
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
