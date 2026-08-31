---
name: hints-probe
maxIterations: 3
model:
  name: gpt-test
  temperature: 0.0
---
A bundle that exists only so AimonRuntimeHintsTest can watch the real loader ask for resources.
It carries one of every shape the loader knows how to read; nothing ever runs a turn against it.
