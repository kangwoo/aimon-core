package at.aimon.spring.boot.autoconfigure;

/**
 * Which stores back peer memory, selected by {@code aimon.memory.backend}.
 *
 * <p>
 * A selector rather than {@code aimon.memory.enabled}, for the reason {@link KnowledgeBackend} is one: a boolean
 * would have to mean both "build me stores" and "use the ones I declared". Naming which is meant lets a store
 * bean that contradicts the value be refused by name instead of adopted or ignored at random.
 *
 * <p>
 * <b>Every value here wires the read path only.</b> The stack runs no deriver, no derivation queue and no
 * dreamer, so nothing it builds ever writes a representation — see {@code MemoryAssembly}, which records that as
 * a degradation for every memory deployment regardless of the value chosen here.
 */
public enum MemoryBackend {

    /**
     * No memory: no injected memory part, no memory tools. The default, because memory that is on and empty is
     * indistinguishable from memory that is on and broken — the model simply never mentions anything it should
     * have remembered.
     */
    NONE,

    /**
     * The built-in heap-backed stores. Both are built, so a fixed-peer deployment gets recall, search and observe
     * at once. Nothing here replicates and nothing survives a restart, which makes it a development and
     * single-node choice — and a sharper one than the equivalent knowledge backend, because memory is written
     * during a session and read back in the next one.
     */
    IN_MEMORY,

    /**
     * The application declares its own {@code RepresentationStore}, {@code ObservationStore} or both, and this
     * starter wires what it finds. Declaring only one is a supported deployment rather than an oversight: an
     * observation store alone is the tools without the injected part, and a representation store alone is the
     * injected part without the tools.
     */
    SUPPLIED
}
