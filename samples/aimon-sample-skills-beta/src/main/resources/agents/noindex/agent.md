---
name: noindex
maxIterations: 3
model:
  name: scripted
---

You are the deliberately misconfigured agent.

This bundle ships a skill directory (`skills/orphan/`) and no `skills/index`. That is a packaging mistake a real
integrator makes: the skill is right there in the jar, the author can see it, and at run time the agent is
offered nothing — because a jar has no listable directory, so the index is the only enumeration there is.

The mistake is not fixable from the framework's side, but being quiet about it is. `noindex` exists so the
packaging tier can assert that this situation produces a warning naming the base path, rather than a debug line
nobody has their level turned up for.
