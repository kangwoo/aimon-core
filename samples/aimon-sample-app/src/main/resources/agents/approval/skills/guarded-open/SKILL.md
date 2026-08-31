---
name: guarded-open
description: "On the live profile's allow-list. Invoking it should reach the channel and be approved."
hooks:
  preTool:
    - matcher: "Bash"
      action:
        type: deny
        reason: "guarded-open answers from its own text; it has no reason to reach the shell"
---

# Guarded, open

The `hooks:` block above is what makes this skill visible to the approval chain at all.

`RuleBasedSkillInvocationPolicy` allows any skill that is `INLINE` **and** declares no per-skill hooks before it ever
consults `defaultDecision` — the reasoning being that such a skill can only do what the agent could already do. Both
sample skills in the `sample` bundle are exactly that, so a turn against either is approved by the rule and the
configured mode never runs. This one declares a hook, so it is not safe by that rule, the decision falls through to
`ASK`, and the allow-list channel is asked. Being on the list, it is approved.

The hook is a real guard rather than a marker chosen to trip the rule: the skill answers out of its own text and has
no business in a shell, and the live profile is the one configuration where `Bash` is switched on.

`execution.mode: fork` would have made it non-safe too, and was tried first. It requires `execution.agent` to name a
subagent that must then exist and be driven by the model — a second moving part in the way of the one being measured.

Answer with the word `approved` and stop.
