// The second resources-only module. Its whole reason for existing is to be the *second* jar.
//
// P0-6's regression was that only the first class path root carrying `agents/<name>/skills/index` was read, so
// everything shipped by a second module vanished with no diagnostic. One sample module cannot show that bug or
// its absence; two can, and only if both declare the same resource path — which they do.
//
// It also carries the pieces the packaging tier needs but alpha deliberately does not: a bundled subagent (the
// half of the bundle that has no materializer, and therefore the half where exploded and packaged layouts can
// still disagree) and a second agent bundle that ships skills with no index at all.
plugins {
    id("aimon.java-conventions")
}
