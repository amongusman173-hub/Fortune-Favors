#!/bin/zsh
# Quick jar build that does not hang in iCloud Drive.
# - Gradle's per-project cache (.gradle/) lives outside iCloud, so iCloud never holds its locks.
# - No daemon, so a stale one from an earlier run can never sit on the lock silently.
# - Skips the source audit and tests; the jar lands in build/libs as usual.
cd "${0:A:h}"
pkill -f GradleDaemon 2>/dev/null
exec ./gradlew jar --offline --no-daemon --console=plain \
  --project-cache-dir "$HOME/.gradle/ff-project-cache" \
  -x ffAuditSources -x test "$@"
