# Runtime image only: the jar is built on the host with ./mvnw package (bench/run-all.sh does it).
# Building inside Docker would re-download all Maven dependencies on every image build.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY target/reservation-engine-*.jar app.jar

# Fixed heap so the three benchmark instances cannot grow and starve PostgreSQL/k6 of memory.
ENV JAVA_OPTS="-Xms256m -Xmx512m"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
