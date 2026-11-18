#!/usr/bin/env bash
# Compile the Android game's pure rules for the local web preview.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
rules="$root/app/src/main/java/com/jiacimu/lulu/games"
cache="${LULU_PREVIEW_KOTLIN_CACHE:-${TMPDIR:-/tmp}/lulu-rules-jvm}"
mkdir -p "$cache"
jars=(
  org/jetbrains/kotlin/kotlin-compiler-embeddable/2.1.10/kotlin-compiler-embeddable-2.1.10.jar
  org/jetbrains/kotlin/kotlin-stdlib/2.1.10/kotlin-stdlib-2.1.10.jar
  org/jetbrains/kotlin/kotlin-script-runtime/2.1.10/kotlin-script-runtime-2.1.10.jar
  org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar
  org/jetbrains/kotlin/kotlin-daemon-embeddable/2.1.10/kotlin-daemon-embeddable-2.1.10.jar
  org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar
  org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar
  org/jetbrains/annotations/13.0/annotations-13.0.jar
  org/json/json/20240303/json-20240303.jar
)
for jar in "${jars[@]}"; do
  name="$cache/${jar##*/}"
  if [[ ! -s "$name" ]]; then
    partial=$(mktemp "$cache/.download.XXXXXX")
    curl --fail --silent --show-error --location --retry 2 "https://repo.maven.apache.org/maven2/$jar" -o "$partial"
    mv "$partial" "$name"
  fi
done
work=$(mktemp -d "$cache/run.XXXXXX")
trap 'rm -rf "$work"' EXIT
# Both source files currently colocate physical options/execution with Android's
# live model context. Keep the pure prefix verbatim and omit the live gateway.
for actor in IslandActorPlay RelicActorPlay; do
  if [[ $(awk '/^    fun facts\(/{ count++ } END{ print count+0 }' "$rules/$actor.kt") != 1 ]]; then
    echo "Check the preview extraction boundary in $actor.kt" >&2
    exit 1
  fi
  awk '/^    fun facts\(/ { print "}"; exit } !/^import com\.jiacimu\.lulu\.ai\./' "$rules/$actor.kt" > "$work/$actor.kt"
done
sources=(ExpeditionRules BlockIslandRules IslandExpeditionTrail IslandGarden IslandGuardianEncounter
  IslandProgression IslandRareEvents IslandSupplyCaches IslandTargetAction IslandTravel IslandWildlife
  IslandChallengeBoard IslandHouseProjects IslandWorkbenchRecipes IslandManualMovement IslandBuildingControls
  WorldFollowPath GameWorldEngine GameCoopPacing RelicEscapeRules RelicEscapeWorld RelicArsenal
  RelicCombatSkills RelicContractBoard RelicCoopTravel RelicObjectiveGuide
  RelicQuickAction RelicChestPresentation)
for i in "${!sources[@]}"; do sources[i]="$rules/${sources[i]}.kt"; done
libraries="$cache/kotlin-stdlib-2.1.10.jar:$cache/json-20240303.jar:$cache/annotations-13.0.jar"
java -cp "$cache/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -language-version 2.0 -no-stdlib -no-reflect -classpath "$libraries" \
  -d "$work/rules.jar" "${sources[@]}" "$work"/{IslandActorPlay,RelicActorPlay}.kt "$rules/../data/CharacterSceneCadence.kt" "$root/preview/arcade/RulesPreview.kt"
java -cp "$work/rules.jar:$libraries" com.jiacimu.lulu.games.RulesPreviewKt
