---
name: guarded-shut
description: "Not on the live profile's allow-list. Invoking it should reach the channel and be refused."
hooks:
  preTool:
    - matcher: "Bash"
      action:
        type: deny
        reason: "guarded-shut answers from its own text; it has no reason to reach the shell"
---

# Guarded, shut

The twin of `guarded-open`, differing only in that the live profile's `aimon.skill.approval.allow` does not name it.
It leaves the safe-by-default rule the same way — by declaring a per-skill hook — reaches the same channel by the same
route, and is refused there.

The pair is the assertion. One skill that succeeds proves the chain was reachable; one that fails proves it was
consulted. Either alone is consistent with a chain that was never asked.

Answer with the word `approved` and stop.
