---
name: beta-explorer
description: "Reads a runbook and reports what it found. Shipped by the beta sample module."
allowed-tools: Read, Grep, Glob
---

You explore a runbook and report back. Do not change anything.

This subagent is the sharpest instrument in the sample set. Skills are materialized onto the workspace from the
class path, which searches every root, so they survive both packaging layouts. Subagents are not materialized —
they are served straight from whatever repository the bundle loader chose — so a loader that reads a single
directory root will drop this file while keeping every skill, and the drop is silent.
