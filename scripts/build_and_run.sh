UUID=$1
PORT=$2
FRAMEWORK=$3
WORKDIR="./uploads/$UUID"
IMG="sandbox-$UUID"
CONTAINER="sandbox-$UUID"

# 이미지 빌드
docker build -t $IMG $WORKDIR || exit 1

# 실행 포트 매핑
if [ "$FRAMEWORK" = "spring" ]; then
  APPPORT=8080
elif [ "$FRAMEWORK" = "react" ]; then
  APPPORT=3000
else
  APPPORT=8000
fi

# 컨테이너 실행
docker run -d --rm --name $CONTAINER -p $PORT:$APPPORT --memory=512m --cpus=1 $IMG
