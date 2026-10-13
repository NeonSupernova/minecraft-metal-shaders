#!/bin/bash
# run.sh (2026-10-11): one showcase run (mcbench cine mode, see mcbench/CineShots.java) in the showcase world.
#   run.sh <spec.json> <label> [--dh] [--rec] [--rd=N] [--out=native|1440|1080] [extra -D... jvm args]
# --dh: instance "mcopt DEV-DH" (Distant Horizons) instead of "mcopt DEV"; --rec: record the clips (mcopt recorder, 3024x1700
# crop of the 3024x1964 frame, native pixels, 60 fps) into rec/<label>.mp4 (+ .csv with each frame's bench phase).
# The spec and the video go through a directory without spaces (mcbench-run splits --jvm on whitespace): $CINE_TMP.
set -e
W="/Users/gaelquintanilla/Documents/Minecraft Metal Shaders/work"; S="$W/showcase"
SPEC="${1:?usage: run.sh <spec.json> <label> [--dh] [--rec] [jvm args]}"; LABEL="${2:?label}"; shift 2
T="${CINE_TMP:-/private/tmp/claude-501/-Users-gaelquintanilla-Documents-Minecraft-Metal-Shaders/a85d8bd1-3bde-4782-a4eb-336263be40d6/scratchpad/cine}"
mkdir -p "$T" "$S/rec"
INST="mcopt DEV"; REC=""; EXTRA=""; RDIST=""; OUTH=""
for a in "$@"; do
  case "$a" in
    --dh) INST="mcopt DEV-DH" ;;
    --rec) REC=1 ;;
    --rd=*) RDIST="${a#--rd=}" ;;
    --out=*) OUTH="${a#--out=}" ;;
    *) EXTRA="$EXTRA $a" ;;
  esac
done
cp "$SPEC" "$T/$LABEL.json"
# --rd=N: the instance's render distance for this run only (put back afterwards)
OPT="$HOME/Library/Application Support/PrismLauncher/instances/$INST/minecraft/options.txt"
if [ -n "$RDIST" ]; then
  OLD_RD="$(sed -n 's/^renderDistance://p' "$OPT")"
  trap 'sed -i "" "s/^renderDistance:.*/renderDistance:$OLD_RD/" "$OPT"' EXIT
  sed -i "" "s/^renderDistance:.*/renderDistance:$RDIST/" "$OPT"
fi
SOD="$W/release/sodium-fabric-0.9.2+mc26.3.jar"
MODS=(--mods "$S/jars/mcopt-shade-FT2.12-rec.jar" --mods "$SOD")
if [ "$INST" = "mcopt DEV-DH" ]; then
  MODS+=(--mods "$W/release/DistantHorizons-3.3.4-26.3-fabric-neoforge.jar" --mods "$W/release/fabric-api-0.161.0+26.3.jar")
fi
JVM="-Dmcopt.shade=true -Dmcbench.standardShots=false -Dmcbench.world=mcbench-showcase -Dmcbench.torches=false -Dmcbench.timeoutSec=5400 -Dmcbench.cine=$T/$LABEL.json"
# --out=native|1440|1080: the shaders' output (window) resolution (mcopt.shade.output)
# (without --out: a 16:9 crop of the native frame, 3024x1700; with --out=native|1440|1080: the whole frame at its own pixels)
CROP="0,132,3024,1700"; RSIZE="3024x1700"
if [ -n "$OUTH" ]; then
  EXTRA="$EXTRA -Dmcopt.shade.output=$OUTH"
  CROP=""
  case "$OUTH" in native) RSIZE="3024x1964" ;; 1440) RSIZE="2218x1440" ;; 1080) RSIZE="1664x1080" ;; *) RSIZE="0x$OUTH" ;; esac
fi
if [ -n "$REC" ]; then
  rm -f "$T/$LABEL.mp4" "$T/$LABEL.mp4.csv" "$T/$LABEL.mp4.log"
  JVM="$JVM -Dmcopt.rec=$T/$LABEL.mp4 -Dmcopt.rec.phases=cine ${CROP:+-Dmcopt.rec.crop=$CROP} -Dmcopt.rec.size=$RSIZE -Dmcopt.rec.bitrate=90M -Dmcopt.rec.fps=60"
fi
"$W/bin/mcbench-run" --direct --instance "$INST" "${MODS[@]}" --scenes shots --label "$LABEL" --timeout-min 95 --jvm "$JVM $EXTRA" 2>&1 | tail -3
if [ -n "$REC" ]; then cp "$T/$LABEL".mp4* "$S/rec/" 2>/dev/null || echo "no video"; fi
