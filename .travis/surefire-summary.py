"""Print per-test skip reasons and the overall totals from target/surefire-reports.

Surefire 2.10 records @Ignore skips without a message (and a class-level @Ignore
as one testcase named after the class), so the matching @Ignore / assume line
from the test source is printed with its file:line.

The totals line carries a per-run nonce (also written to /tmp/seal-log-nonce) so
.travis/wait-for-log.sh can tell this run's archived summary from an earlier one.
"""
import glob
import os
import re
import sys
import uuid
import xml.etree.ElementTree as ET


def read_source(classname):
    path = os.path.join("src/test/java", classname.split("$")[0].replace(".", "/") + ".java")
    try:
        with open(path, encoding="utf-8") as f:
            return path, f.readlines()
    except OSError:
        return path, None


def source_reason(classname, method):
    path, lines = read_source(classname)
    if lines is None:
        return None
    name = re.sub(r"\[.*$", "", method)
    for i, line in enumerate(lines):
        if re.search(r"\bvoid\s+%s\s*\(" % re.escape(name), line):
            for j in range(max(0, i - 4), i):
                if "@Ignore" in lines[j]:
                    return "disabled upstream by %s at %s:%d" % (lines[j].strip(), path, j + 1)
            for k in range(i + 1, min(len(lines), i + 40)):
                if re.search(r"assume", lines[k], re.IGNORECASE):
                    return "assumption not met: %s (%s:%d)" % (lines[k].strip(), path, k + 1)
    simple = classname.split("$")[0].split(".")[-1]
    for i, line in enumerate(lines):
        if re.search(r"\bclass\s+%s\b" % re.escape(simple), line):
            for j in range(max(0, i - 6), i):
                if "@Ignore" in lines[j]:
                    return "whole class disabled upstream by %s at %s:%d" % (
                        lines[j].strip(), path, j + 1)
            break
    return None


tests = failures = errors = skipped = 0
skips = []
for path in sorted(glob.glob("target/surefire-reports/TEST-*.xml")):
    root = ET.parse(path).getroot()
    tests += int(root.get("tests", 0))
    failures += int(root.get("failures", 0))
    errors += int(root.get("errors", 0))
    skipped += int(root.get("skipped", 0))
    for case in root.iter("testcase"):
        sk = case.find("skipped")
        if sk is None:
            continue
        message = (sk.get("message") or "").strip()
        if not message:
            message = source_reason(case.get("classname", ""), case.get("name", "")) or "(no message)"
        skips.append("%s.%s: %s" % (case.get("classname"), case.get("name"), message))

nonce = uuid.uuid4().hex[:12]
with open("/tmp/seal-log-nonce", "w") as f:
    f.write(nonce)

print("Skipped tests (%d):" % len(skips))
for line in skips:
    print("  SKIPPED " + line)
print("Surefire totals: tests=%d passed=%d failures=%d errors=%d skipped=%d (run %s)"
      % (tests, tests - failures - errors - skipped, failures, errors, skipped, nonce))
sys.exit(0 if tests > 0 and failures == 0 and errors == 0 else 1)
