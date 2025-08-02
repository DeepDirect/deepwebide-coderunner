#!/bin/bash
set -euo pipefail  # 더 엄격한 오류 처리

UUID=$1
PORT=$2
FRAMEWORK=$3
WORKDIR=$4  # 임시 작업 디렉토리, 반드시 절대경로로 들어옴!
IMG="sandbox-$UUID"
CONTAINER="sandbox-$UUID"

echo "========================================="
echo "Starting build and run process..."
echo "UUID: $UUID"
echo "PORT: $PORT"
echo "FRAMEWORK: $FRAMEWORK"
echo "WORKDIR: $WORKDIR"
echo "========================================="

# 에러 발생 시 정리 함수
cleanup_on_error() {
    echo "Error occurred. Cleaning up..."
    docker rm -f "$CONTAINER" 2>/dev/null || true
    docker rmi -f "$IMG" 2>/dev/null || true
}
trap cleanup_on_error ERR

# 작업 디렉토리 확인
if [ ! -d "$WORKDIR" ]; then
    echo "Error: Work directory $WORKDIR does not exist"
    exit 1
fi

echo "Work directory contents:"
ls -la "$WORKDIR"

# 프로젝트 구조 정규화 (필요하면 유지, 현재 호출만, 상세는 서버에서 실행해도 됨)
normalize_project_structure() {
    local workdir=$1
    local framework=$2

    echo "Normalizing project structure for $framework..."

    case $framework in
        "spring")
            local spring_dir=""
            for dir in "$workdir"/*; do
                if [ -d "$dir" ] && ([ -f "$dir/gradlew" ] || [ -f "$dir/build.gradle" ] || [ -f "$dir/pom.xml" ]); then
                    spring_dir="$dir"
                    break
                fi
            done
            if [ -n "$spring_dir" ] && [ "$spring_dir" != "$workdir" ]; then
                echo "Found Spring Boot project in: $spring_dir"
                temp_dir="${workdir}_temp"
                mkdir -p "$temp_dir"
                cp -r "$spring_dir"/* "$temp_dir/"
                find "$workdir" -mindepth 1 -not -name "Dockerfile" -exec rm -rf {} +
                cp -r "$temp_dir"/* "$workdir/"
                rm -rf "$temp_dir"
                echo "Spring Boot project structure normalized"
            fi
            ;;
        "react")
            local react_dir=""
            for dir in "$workdir"/*; do
                if [ -d "$dir" ] && [ -f "$dir/package.json" ]; then
                    react_dir="$dir"
                    break
                fi
            done
            if [ -n "$react_dir" ] && [ "$react_dir" != "$workdir" ]; then
                echo "Found React project in: $react_dir"
                temp_dir="${workdir}_temp"
                mkdir -p "$temp_dir"
                cp -r "$react_dir"/* "$temp_dir/"
                find "$workdir" -mindepth 1 -not -name "Dockerfile" -exec rm -rf {} +
                cp -r "$temp_dir"/* "$workdir/"
                rm -rf "$temp_dir"
                echo "React project structure normalized"
            fi
            ;;
        "fastapi")
            local fastapi_dir=""
            for dir in "$workdir"/*; do
                if [ -d "$dir" ] && ([ -f "$dir/requirements.txt" ] || [ -f "$dir/main.py" ]); then
                    fastapi_dir="$dir"
                    break
                fi
            done
            if [ -n "$fastapi_dir" ] && [ "$fastapi_dir" != "$workdir" ]; then
                echo "Found FastAPI project in: $fastapi_dir"
                temp_dir="${workdir}_temp"
                mkdir -p "$temp_dir"
                cp -r "$fastapi_dir"/* "$temp_dir/"
                find "$workdir" -mindepth 1 -not -name "Dockerfile" -exec rm -rf {} +
                cp -r "$temp_dir"/* "$workdir/"
                rm -rf "$temp_dir"
                echo "FastAPI project structure normalized"
            fi
            ;;
    esac
}
normalize_project_structure "$WORKDIR" "$FRAMEWORK"

echo "Updated directory contents:"
ls -la "$WORKDIR"

# 프레임워크별 필수 파일 확인
case $FRAMEWORK in
    "spring")
        if [ ! -f "$WORKDIR/build.gradle" ] && [ ! -f "$WORKDIR/pom.xml" ] && [ ! -f "$WORKDIR/gradlew" ]; then
            echo "Error: No build.gradle, pom.xml, or gradlew found in $WORKDIR"
            find "$WORKDIR" -name "gradlew" -o -name "pom.xml" -o -name "build.gradle"
            exit 1
        fi
        ;;
    "react")
        if [ ! -f "$WORKDIR/package.json" ]; then
            echo "Error: No package.json found in $WORKDIR"
            find "$WORKDIR" -name "package.json"
            exit 1
        fi
        ;;
    "fastapi")
        if [ ! -f "$WORKDIR/main.py" ]; then
            echo "Error: No main.py found in $WORKDIR"
            find "$WORKDIR" -name "requirements.txt" -o -name "main.py"
            exit 1
        fi
        ;;
esac

# Dockerfile 존재 확인
if [ ! -f "$WORKDIR/Dockerfile" ]; then
    echo "Error: Dockerfile not found in $WORKDIR"
    exit 1
fi

echo "Dockerfile content:"
cat "$WORKDIR/Dockerfile"

# 이전 컨테이너/이미지 정리
echo "Cleaning up previous containers and images..."
docker rm -f "$CONTAINER" 2>/dev/null || true
docker rmi -f "$IMG" 2>/dev/null || true

# Docker 빌드
echo "Building Docker image..."
if ! docker build --progress=plain --no-cache -t "$IMG" "$WORKDIR"; then
    echo "ERROR: Docker build failed"
    exit 1
fi

# 내부 앱 포트 결정
case $FRAMEWORK in
    "spring")
        APPPORT=8080
        ;;
    "react")
        APPPORT=3000
        ;;
    "fastapi")
        APPPORT=8000
        ;;
    *)
        echo "Error: Unsupported framework: $FRAMEWORK"
        exit 1
        ;;
esac

echo "Starting container with port mapping $PORT:$APPPORT..."

# 컨테이너 실행
if docker run -d \
    --rm \
    --name "$CONTAINER" \
    -p "$PORT":"$APPPORT" \
    --memory=2g \
    --cpus=2 \
    "$IMG"; then

    echo "Container started successfully!"
    echo "Container: $CONTAINER"
    echo "Port mapping: $PORT:$APPPORT"

    # 헬스 체크
    echo "Waiting for container to be ready..."
    for i in {1..30}; do
        if docker ps --format "table {{.Names}}\t{{.Status}}" | grep -q "$CONTAINER.*Up"; then
            echo "Container is running successfully (attempt $i/30)"
            sleep 5
            case $FRAMEWORK in
                "react"|"fastapi")
                    if curl -f -s "http://localhost:$PORT" >/dev/null 2>&1; then
                        echo "Application is responding on port $PORT"
                    else
                        echo "Note: Application may still be starting up..."
                    fi
                    ;;
                "spring")
                    echo "Spring Boot application starting... (may take a few moments)"
                    ;;
            esac
            exit 0
        fi
        if [ $i -eq 30 ]; then
            echo "Container health check failed after 30 attempts"
            echo "Container logs:"
            docker logs "$CONTAINER" 2>/dev/null || echo "Could not retrieve logs"
            exit 1
        fi
        echo "Checking container status... ($i/30)"
        sleep 2
    done
else
    echo "Error: Failed to start container"
    exit 1
fi
