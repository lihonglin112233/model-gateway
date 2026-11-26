#!/bin/bash
# 构建 Model Gateway APK（纯本机工具链，不依赖 Gradle / Android Studio）
set -e
cd "$(dirname "$0")"

SDK=${SDK:-/root/workspace/tools/p34/android-34}
BT=${BT:-/root/workspace/tools/bt/android-14}
AAPT2=${AAPT2:-/tmp/aapt2}
SRC_ROOT=${SRC_ROOT:-/root/workspace/model-gateway/qsdbl-model-gateway-2d2dc95}
ROOTFS=${ROOTFS:-/tmp/stage1}

AJ="$SDK/android.jar"
OUT=build

rm -rf $OUT/classes $OUT/dex $OUT/apkdir $OUT/unsigned.apk $OUT/stage.apk $OUT/aligned.apk
mkdir -p $OUT/classes $OUT/dex $OUT/assets $OUT/apkdir

echo "== 1/6 打包网关源码"
# 归档内保留 app/ 前缀：APK 里解到 <rootfs>/root 后正好是 /root/app/{gateway,admin}
rm -rf /tmp/appstage && mkdir -p /tmp/appstage/app/data
cp -r "$SRC_ROOT/gateway" "$SRC_ROOT/admin" /tmp/appstage/app/
find /tmp/appstage -name __pycache__ -type d -prune -exec rm -rf {} + 2>/dev/null || true
( cd /tmp/appstage && tar -cf - app ) | gzip -9 > $OUT/assets/app.tar.gz

echo "== 2/6 打包 rootfs（剔除项目代码，避免与 app.tar.gz 重复）"
rm -rf "$ROOTFS/root/app"
( cd "$ROOTFS" && tar -cf - . ) | gzip -9 > $OUT/assets/rootfs.tar.gz

echo "== 3/6 复制 proot 运行时"
cp /root/work/apk/dsh/lib/arm64-v8a/libproot.so        $OUT/assets/proot
cp /root/work/apk/dsh/lib/arm64-v8a/libproot-loader.so $OUT/assets/proot-loader
cp /root/work/apk/dsh/lib/arm64-v8a/libtalloc.so       $OUT/assets/libtalloc.so
cp /root/work/apk/dsh/lib/arm64-v8a/libandroid-shmem.so $OUT/assets/libandroid-shmem.so
mkdir -p $OUT/lib/arm64-v8a
cp /root/work/apk/dsh/lib/arm64-v8a/libproot.so         $OUT/lib/arm64-v8a/
cp /root/work/apk/dsh/lib/arm64-v8a/libtalloc.so        $OUT/lib/arm64-v8a/
cp /root/work/apk/dsh/lib/arm64-v8a/libandroid-shmem.so $OUT/lib/arm64-v8a/

echo "== 4/6 编译 Java"
javac -source 8 -target 8 -encoding UTF-8 -bootclasspath "$AJ" \
  -cp "$BT/core-lambda-stubs.jar" -d $OUT/classes $(find src -name '*.java')
"$BT/d8" --min-api 24 --lib "$AJ" --output $OUT/dex $(find $OUT/classes -name '*.class')

echo "== 5/6 资源与打包"
$AAPT2 compile --dir res -o $OUT/res.zip
$AAPT2 link -o $OUT/unsigned.apk -I "$AJ" --manifest AndroidManifest.xml \
  $OUT/res.zip --min-sdk-version 24 --target-sdk-version 34 -A $OUT/assets
( cd $OUT/apkdir && unzip -q ../unsigned.apk && cp ../dex/classes.dex . \
  && mkdir -p lib/arm64-v8a && cp ../lib/arm64-v8a/*.so lib/arm64-v8a/ \
  && zip -q -r -X ../stage.apk . -x 'META-INF/*' )
zipalign -f -p 4 $OUT/stage.apk $OUT/aligned.apk

echo "== 6/6 签名"
apksigner sign --ks build/mg.jks --ks-pass pass:mgpass123 --key-pass pass:mgpass123 \
  --ks-key-alias mg --min-sdk-version 24 --v1-signing-enabled true \
  --v2-signing-enabled true --v3-signing-enabled true \
  --out $OUT/ModelGateway.apk $OUT/aligned.apk
apksigner verify --print-certs $OUT/ModelGateway.apk | head -3
ls -lh $OUT/ModelGateway.apk
echo "完成：$OUT/ModelGateway.apk"
