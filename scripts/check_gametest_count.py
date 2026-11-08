#!/usr/bin/env python3
"""Fail closed on a GameTest server run that silently did nothing.

Gradle reports BUILD SUCCESSFUL even when the GameTest server fails to start
(e.g. a required mod missing from the runtime classpath) or ran zero tests,
so a green `./gradlew runGameTestServer` exit code alone proves nothing. This
checks the run's own log for unambiguous evidence: the server must not have
failed to start, there must be no reported required-test failures, and at
least one required test must have actually completed and passed.

Simpler than dynamic-vehicles' sibling script of the same name: this mod has
no per-ticket isolated GameTest namespace profiles (no m478/m454-style
build.gradle machinery to parse), so there is nothing to select beyond "every
test the default run enables" -- just read the log's own completion summary.
"""
import re
import sys
from pathlib import Path


class RuntimeEvidenceError(ValueError):
    """The GameTest log cannot prove a complete, non-trivial run."""


def verify_runtime_log(log_text):
    if re.search(r"Failed to start the minecraft server", log_text, re.I):
        raise RuntimeEvidenceError("GameTest server startup failed")
    if re.search(r"\b\d+\s+required tests failed\b", log_text):
        raise RuntimeEvidenceError("required GameTest failure evidence")
    completed = list(re.finditer(r"=+\s*(\d+)\s+GAME TESTS COMPLETE\s+IN\s+", log_text))
    passed = list(re.finditer(r"All\s+(\d+)\s+required tests passed", log_text))
    if len(completed) != 1 or len(passed) != 1:
        raise RuntimeEvidenceError("missing or ambiguous completion/pass summary")
    if completed[0].end() > passed[0].start() or completed[0].group(1) != passed[0].group(1):
        raise RuntimeEvidenceError("completion/pass summaries are not one consistent terminal pair")
    count = int(completed[0].group(1))
    if count == 0:
        raise RuntimeEvidenceError("zero GameTests ran -- the server likely never registered any")
    return count


def main(argv):
    if len(argv) != 2:
        raise SystemExit(f"usage: {argv[0]} <gametest.log>")
    try:
        passed = verify_runtime_log(Path(argv[1]).read_text())
    except (OSError, RuntimeEvidenceError) as error:
        raise SystemExit(f"GameTest count guard failed: {error}") from error
    print(f"GameTest count guard passed: {passed} required cases")


if __name__ == "__main__":
    main(sys.argv)
