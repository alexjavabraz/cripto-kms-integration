FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM amazoncorretto:21
WORKDIR /app

RUN mkdir -p /tmp /app/tmp && \
    yum update -y && \
    yum install -y shadow-utils && \
    groupadd -g 10001 appgroup && \
    useradd -r -u 10001 -g appgroup app && \
    chown -R app:appgroup /app/tmp && \
    yum clean all

VOLUME /tmp
VOLUME /app/tmp

USER app

COPY --from=build /app/target/kms-integration-1.0.0.jar /app/app.jar

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/app/tmp -Dserver.tomcat.basedir=/app/tmp"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
