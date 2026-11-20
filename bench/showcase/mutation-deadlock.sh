#!/usr/bin/env bash
# Mutation example: remove the deadlock fix of stage 3 (pessimistic strategy releases an empty
# shard's row lock with ROLLBACK TO SAVEPOINT) and run the sharded concurrency test; then restore
# the fix and run it again. Real test runs.
#   bash bench/showcase/mutation-deadlock.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
F=src/main/java/io/github/samettns/reservation/strategy/PessimisticLockingStrategy.java
TEST='StrategyConcurrencyTest#correctStrategiesNeverOversell'
LOG=/tmp/mutation-deadlock.log
run() {
  ./mvnw -B -ntp -q test -Dtest="$TEST" -Dsurefire.failIfNoSpecifiedTests=false > $LOG 2>&1
  local code=$?
  echo "  exit code: $code  ($([ $code = 0 ] && echo 'tests GREEN' || echo 'tests RED'))"
  echo "  'deadlock detected' in log: $(grep -c 'deadlock detected' $LOG)"
  grep -E '^\[pessimistic stock=100 shards=8\]' $LOG | head -1 | sed 's/^/  /'
  grep -E 'Tests run: [0-9]+, Failures' $LOG | tail -1 | sed 's/^/  /'
}
cp "$F" /tmp/Pessimistic.bak
trap 'cp /tmp/Pessimistic.bak "$F"' EXIT
echo "== MUTATION: ROLLBACK TO SAVEPOINT removed (empty shard's row lock is kept)"
sed -i 's/jdbc.sql("ROLLBACK TO SAVEPOINT " + SAVEPOINT).update();/\/\/ MUTATION: lock kept/' "$F"
echo "  applied: $(grep -c MUTATION "$F") line"
run
cp /tmp/Pessimistic.bak "$F"
echo "== FIX RESTORED"
run
