# 固定已核验的Temurin21.0.12+8官方镜像摘要；镜像自带curl，构建无需访问apt或升级系统包。
FROM eclipse-temurin@sha256:61d6c7b34d36aee3f45d043101259f97f3c6d428dc2a6f75513789983c5e254f
RUN command -v curl
ARG MODULE
WORKDIR /app
COPY ${MODULE}/target/${MODULE}-0.1.0-SNAPSHOT.jar /app/app.jar
ENTRYPOINT ["java", "-Xmx384m", "-jar", "/app/app.jar"]
