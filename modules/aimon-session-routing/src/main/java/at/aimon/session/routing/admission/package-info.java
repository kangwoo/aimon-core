/**
 * Node turn admission: the optional gate a host plugs into the router to decide, per node, whether a pass may start
 * here now or must be left for a node with capacity. Design: {@code docs/design/session/routing.md} §8.4.
 */
package at.aimon.session.routing.admission;
