package com.sandbox.sandbox_server.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class DockerfileUtil {
    public static void generateDockerfile(Path projectDir, String framework) throws IOException {
        String content;
        if ("spring".equals(framework)) {
            content = """
                FROM openjdk:17-slim
                WORKDIR /app
                COPY . .
                RUN apt-get update && apt-get install -y findutils
                RUN chmod +x gradlew
                RUN ./gradlew build --no-daemon
                EXPOSE 8080
                CMD ["java", "-jar", "build/libs/*.jar"]
                """;
        } else if ("react".equals(framework)) {
            content = """
                FROM node:18
                WORKDIR /app
                COPY . .
                RUN npm install && npm run build
                EXPOSE 3000
                CMD ["npm", "start"]
                """;
        } else if ("fastapi".equals(framework)) {
            content = """
                FROM python:3.11
                WORKDIR /app
                COPY . .
                RUN pip install -r requirements.txt
                EXPOSE 8000
                CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
                """;
        } else {
            throw new IOException("지원하지 않는 프레임워크입니다.");
        }
        Files.writeString(projectDir.resolve("Dockerfile"), content);
    }
}
