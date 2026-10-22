#!/bin/bash
# Checks that the SHA-256 values pinned in the app match the speech-model archives published by Vosk.
# A mismatch fails the build (the app would reject the download); a network error only warns.
cd "$(dirname "$0")/.."
K=app/src/main/java/ai/neuroshield/app/listen/ModelHashes.kt
L=app/src/main/java/ai/neuroshield/app/listen/Listen.kt
fail=0
for lang in RU KK EN; do
  url=$(grep -o "https://alphacephei.com/vosk/models/[^\"]*" $L | sed -n "$(( $(echo "RU KK EN" | tr ' ' '\n' | grep -n "^$lang$" | cut -d: -f1) ))p")
  pinned=$(grep "SHA_$lang" $K | grep -o '"[0-9a-f]*"' | tr -d '"')
  actual=$(curl -fsSL --max-time 300 "$url" | sha256sum | cut -d' ' -f1)
  if [ -z "$actual" ] || [ "$actual" = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" ]; then
    echo "::warning title=speech model $lang::could not download $url"; continue
  fi
  echo "::notice title=speech model $lang::$url sha256=$actual"
  if [ "$pinned" != "$actual" ]; then echo "::error title=speech model $lang::pinned '$pinned' != published '$actual'"; fail=1; fi
done
exit $fail
