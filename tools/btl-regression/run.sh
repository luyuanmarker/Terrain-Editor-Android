#!/bin/sh
# BTL 读写无头回归测试（见 README.md）
#   sh tools/btl-regression/run.sh <语料目录>
#   sh tools/btl-regression/run.sh --conq <conquest.btl> <world.bin>
#   sh tools/btl-regression/run.sh --bin <world.bin>
#   sh tools/btl-regression/run.sh --one <map.btl> [paint:x,y,group ...]
set -e

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
build="$here/build"

rm -rf "$build"
mkdir -p "$build/src"
cp -R "$root/app/src/main/java/com" "$build/src/"
rm -f "$build/src/com/xckeji/bj/MainActivity.java" \
      "$build/src/com/xckeji/bj/render/HexMapView.java" \
      "$build/src/com/xckeji/bj/model/GeneralData.java"
cp -R "$here/src/." "$build/src/"

javac -encoding UTF-8 -nowarn -d "$build/classes" $(find "$build/src" -name '*.java')

case "$1" in
  --conq) shift; java -cp "$build/classes" Conq "$1" "$2" ;;
  --bin)  shift; java -cp "$build/classes" BinTest "$1" ;;
  --one)  shift; java -cp "$build/classes" Harness "$@" ;;
  *)      java -cp "$build/classes" RT "$@"; echo; java -cp "$build/classes" Bulk "$@" ;;
esac
