#!/bin/bash
# Keep the job open until Travis has archived THIS run's test summary (matched by
# the per-run nonce surefire-summary.py printed), so the log harvested right after
# the build finishes is complete. Never changes the build result (after_script).
# Prints a heartbeat to avoid the 10-minute no-output kill.
URL="https://api.travis-ci.com/v3/job/${TRAVIS_JOB_ID}/log.txt"
NONCE=$(cat /tmp/seal-log-nonce 2>/dev/null)
if [ -z "$NONCE" ]; then
  echo "no test summary nonce; nothing to wait for"
  exit 0
fi
MARK="(run $NONCE)"
waited=0
while [ "$waited" -lt 1800 ]; do
  if curl -fsSL "$URL" 2>/dev/null | grep -qF "$MARK"; then
    echo "log archived after ${waited}s"
    exit 0
  fi
  echo "waiting for log archive... (${waited}s)"
  sleep 20
  waited=$((waited + 20))
done
echo "gave up waiting after ${waited}s"
exit 0
