ARG MAVEN_IMAGE
ARG JRE_IMAGE

FROM ${MAVEN_IMAGE} AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package \
    && java -Djarmode=tools -jar target/api-*.jar extract --layers --launcher --destination target/extracted

FROM ${JRE_IMAGE}
WORKDIR /app
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./
EXPOSE 8080 8081
USER 10001:10001
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
