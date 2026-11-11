#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_output_dir=$(mktemp -d)
trap 'rm -rf "$test_output_dir"' EXIT
javac --release 17 -d "$test_output_dir" \
    app/src/main/java/com/ntroll/app/FirewallPlan.java tests/FirewallPlanTest.java app/src/main/java/com/ntroll/app/RuleBackup.java tests/RuleBackupTest.java
java -cp "$test_output_dir" com.ntroll.app.FirewallPlanTest

java -cp "$test_output_dir" com.ntroll.app.RuleBackupTest
