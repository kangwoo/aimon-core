#!/usr/bin/env python3
"""Helper script for the resource-demo skill.

Prints a single confirmation line so the skill can prove that a materialized
script under ${AIMON_SKILL_DIR}/scripts/ is actually executable, not just readable.
"""
import sys


def main() -> None:
    topic = sys.argv[1] if len(sys.argv) > 1 else "sample"
    print(f"resource-demo OK: script executed for topic '{topic}'")


if __name__ == "__main__":
    main()
