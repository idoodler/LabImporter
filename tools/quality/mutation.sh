#!/bin/sh
# Mutation testing of shared/ (docs/quality/RUBRIC.md, "Test effectiveness"): PIT
# changes the compiled export, parse, model and loinc code (plus NumberFormatting)
# one small way at a time and checks that a JVM test fails.
#
#   tools/quality/mutation.sh                  # everything, ~4 minutes on an M-series Mac
#   tools/quality/mutation.sh '-Pmutation.classes=dev.idoodler.labimporter.core.export.*'
#
# PIT (pinned in gradle/libs.versions.toml) runs from Gradle's `:shared:mutationTest`
# task, so nothing is installed globally. It writes the HTML and XML report to
# build/quality/mutation/ and the per-package scores to build/quality/mutation.json.
# PIT's default mutators; Kotlin's generated members and null checks are left out
# (see shared/build.gradle.kts). A mutant PIT couldn't run the tests against (RUN_ERROR)
# is inconclusive: reported, never counted as killed (tools/quality/reports.py). Needs a JDK 17+ (JAVA_HOME) like every Gradle run.
set -e
cd "$(dirname "$0")/../.."
mkdir -p build/quality
./gradlew :shared:mutationTest --console=plain "$@"
python3 tools/quality/reports.py mutation build/quality/mutation/mutations.xml > build/quality/mutation.json
python3 - build/quality/mutation.json <<'EOF'
import json, sys
report = json.load(open(sys.argv[1]))
print(f"Mutation score {report['total']['score_pct']}% ({report['total']['killed']}/{report['total']['total']}, "
      f"{report['total']['inconclusive']} inconclusive)")
for package, score in report["packages"].items():
    print(f"  {package:10} {score['score_pct']:5}%  ({score['killed']}/{score['total']}, {score['inconclusive']} inconclusive)")
print(f"Surviving mutants in clinical code: {len(report['clinical_survivors'])} (build/quality/mutation.json)")
EOF
