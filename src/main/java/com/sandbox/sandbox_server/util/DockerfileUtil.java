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
            WORKDIR /app
            
            # 시스템 패키지 설치
            RUN apt-get update && \\
                apt-get install -y curl wget unzip && \\
                rm -rf /var/lib/apt/lists/*
            
            # Gradle 설치 (gradlew가 없는 경우를 위해)
            RUN wget -q https://services.gradle.org/distributions/gradle-8.5-bin.zip && \\
                unzip -q gradle-8.5-bin.zip && \\
                mv gradle-8.5 /opt/gradle && \\
                rm gradle-8.5-bin.zip
            
            ENV GRADLE_HOME=/opt/gradle
            ENV PATH=$GRADLE_HOME/bin:$PATH
            
            # 프로젝트 파일 복사
            COPY . .
            
            # Gradle 실행 권한 설정
            RUN if [ -f gradlew ]; then chmod +x gradlew; fi
            
            # 빌드 실행 (bootJar 태스크 명시적으로 실행)
            RUN if [ -f gradlew ]; then \\
                    echo "Building with Gradle Wrapper..." && \\
                    ./gradlew clean bootJar --no-daemon -x test --info; \\
                elif [ -f build.gradle ]; then \\
                    echo "Building with system Gradle..." && \\
                    gradle clean bootJar --no-daemon -x test --info; \\
                elif [ -f pom.xml ]; then \\
                    echo "Building with Maven..." && \\
                    apt-get update && apt-get install -y maven && \\
                    mvn clean package spring-boot:repackage -DskipTests; \\
                else \\
                    echo "No build file found!" && \\
                    ls -la && \\
                    exit 1; \\
                fi
            
            # 빌드 결과 확인
            RUN echo "Build completed. Checking for JAR files:" && \\
                find . -name "*.jar" -type f && \\
                echo "Checking build/libs directory:" && \\
                ls -la build/libs/ 2>/dev/null || echo "No build/libs directory"
            
            # Spring Boot JAR 파일 찾기 및 복사 (더 정확한 방법)
            RUN echo "Looking for Spring Boot JAR file..." && \\
                JAR_FILE=$(find build/libs -name "*-boot.jar" -o -name "*-SNAPSHOT.jar" -o -name "*.jar" | grep -v "plain" | head -n 1) && \\
                if [ -z "$JAR_FILE" ]; then \\
                    echo "Trying alternative JAR search..." && \\
                    JAR_FILE=$(find . -name "*.jar" -path "*/build/libs/*" -o -path "*/target/*" | grep -v "plain" | head -n 1); \\
                fi && \\
                if [ -n "$JAR_FILE" ]; then \\
                    echo "Found JAR file: $JAR_FILE" && \\
                    echo "JAR file details:" && \\
                    ls -la "$JAR_FILE" && \\
                    echo "Checking JAR manifest:" && \\
                    jar tf "$JAR_FILE" | grep -E "MANIFEST.MF|BOOT-INF" | head -5 && \\
                    cp "$JAR_FILE" app.jar && \\
                    echo "JAR file copied successfully"; \\
                else \\
                    echo "ERROR: No suitable JAR file found!" && \\
                    echo "Available files in build/libs:" && \\
                    ls -la build/libs/ 2>/dev/null || echo "No build/libs directory" && \\
                    echo "All JAR files found:" && \\
                    find . -name "*.jar" -type f && \\
                    exit 1; \\
                fi
            
            # 최종 JAR 파일 검증
            RUN echo "Final JAR verification:" && \\
                ls -la app.jar && \\
                echo "Checking app.jar manifest:" && \\
                jar tf app.jar | grep -E "MANIFEST.MF|BOOT-INF" | head -5
            
            EXPOSE 8080
            
            # 헬스체크 추가
            HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \\
                CMD curl -f http://localhost:8080/actuator/health || curl -f http://localhost:8080/ || exit 1
            
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