package com.sandbox.sandbox_server.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class DockerfileUtil {
    public static void generateDockerfile(Path projectDir, String framework) throws IOException {
        String content = switch (framework) {
            case "spring" -> generateSpringDockerfile();
            case "react" -> generateReactDockerfile();
            case "fastapi" -> generateFastApiDockerfile();
            default -> throw new IOException("지원하지 않는 프레임워크입니다: " + framework);
        };

        Files.writeString(projectDir.resolve("Dockerfile"), content);
    }

    private static String generateSpringDockerfile() {
        return """
            FROM openjdk:17-slim
            
            # Gradle 설치 (gradlew가 없는 경우를 위해)
            RUN apt-get update && apt-get install -y wget unzip && \\
                wget -q https://services.gradle.org/distributions/gradle-8.5-bin.zip && \\
                unzip -q gradle-8.5-bin.zip && \\
                mv gradle-8.5 /opt/gradle && \\
                rm gradle-8.5-bin.zip && \\
                apt-get clean && \\
                rm -rf /var/lib/apt/lists/*
            
            ENV GRADLE_HOME=/opt/gradle
            ENV PATH=$GRADLE_HOME/bin:$PATH
            
            WORKDIR /app
            COPY . .
            
            # Gradle Wrapper가 있으면 사용, 없으면 시스템 Gradle 사용
            RUN if [ -f gradlew ]; then \\
                    chmod +x gradlew && ./gradlew build --no-daemon -x test; \\
                elif [ -f build.gradle ]; then \\
                    gradle build --no-daemon -x test; \\
                elif [ -f pom.xml ]; then \\
                    apt-get update && apt-get install -y maven && \\
                    mvn clean package -DskipTests; \\
                else \\
                    echo "No build file found" && exit 1; \\
                fi
            
            # JAR 파일 찾기 및 복사
            RUN JAR_FILE=$(find . -name "*.jar" -path "*/build/libs/*" -o -path "*/target/*" | head -n 1) && \\
                if [ -n "$JAR_FILE" ]; then \\
                    echo "Found JAR file: $JAR_FILE" && \\
                    cp "$JAR_FILE" app.jar; \\
                else \\
                    echo "No JAR file found. Available files:" && \\
                    find . -name "*.jar" && \\
                    exit 1; \\
                fi
            
            EXPOSE 8080
            CMD ["java", "-jar", "app.jar"]
            """;
    }

    private static String generateReactDockerfile() {
        return """
            # 빌드 스테이지
            FROM node:20-slim AS builder
            WORKDIR /app
            
            # 시스템 패키지 업데이트 및 필요한 도구 설치
            RUN apt-get update && \\
                apt-get install -y curl && \\
                rm -rf /var/lib/apt/lists/*
            
            # 패키지 파일 복사 (캐시 최적화)
            COPY package*.json ./
            
            # npm 설정 (엔진 호환성 체크 우회)
            RUN npm config set engine-strict false && \\
                npm config set fund false && \\
                npm config set audit false
            
            # 의존성 설치
            RUN npm ci --no-audit --no-fund || \\
                npm install --legacy-peer-deps --no-audit --no-fund
            
            # 소스 코드 복사
            COPY . .
            
            # 빌드 실행
            RUN npm run build
            
            # 빌드 결과 확인
            RUN echo "Build completed. Checking output directories:" && \\
                ls -la && \\
                if [ -d dist ]; then echo "Found dist directory"; ls -la dist/; fi && \\
                if [ -d build ]; then echo "Found build directory"; ls -la build/; fi
            
            # 런타임 스테이지
            FROM node:20-slim
            WORKDIR /app
            
            # 시스템 패키지 설치
            RUN apt-get update && \\
                apt-get install -y curl && \\
                rm -rf /var/lib/apt/lists/*
            
            # serve 설치
            RUN npm install -g serve@14.2.3
            
            # 빌드된 파일 복사 시도 (dist 우선)
            COPY --from=builder /app/dist ./dist
            
            # 디버깅을 위한 package.json 복사
            COPY --from=builder /app/package.json ./package.json
            
            EXPOSE 3000
            
            # 헬스체크 추가
            HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \\
                CMD curl -f http://localhost:3000 || exit 1
            
            # 시작 명령어 (dist 디렉토리 사용)
            CMD ["sh", "-c", "echo 'Starting React application...' && echo 'Available files:' && ls -la && serve -s dist -l 3000 -n"]
            """;
    }

    private static String generateFastApiDockerfile() {
        return """
            FROM python:3.11-slim
            
            # 시스템 패키지 업데이트
            RUN apt-get update && \\
                apt-get install -y curl && \\
                rm -rf /var/lib/apt/lists/*
            
            WORKDIR /app
            
            # requirements.txt 먼저 복사 (캐시 최적화)
            COPY requirements.txt ./
            
            # Python 패키지 설치
            RUN if [ -f requirements.txt ]; then \\
                    pip install --no-cache-dir --upgrade pip && \\
                    pip install --no-cache-dir -r requirements.txt; \\
                else \\
                    echo "Installing default FastAPI packages..." && \\
                    pip install --no-cache-dir fastapi uvicorn; \\
                fi
            
            # 소스 코드 복사
            COPY . .
            
            # main.py 파일 확인
            RUN if [ ! -f main.py ]; then \\
                    echo "ERROR: main.py not found!" && \\
                    echo "Available files:" && ls -la && \\
                    exit 1; \\
                fi
            
            EXPOSE 8000
            CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--reload"]
            """;
    }
}