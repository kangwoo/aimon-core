package at.aimon.core.skill;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable representation of an Agent Skill.
 *
 * <p>
 * An Agent Skill is a reusable knowledge package that teaches an LLM agent how to perform a specific task. It consists
 * of:
 *
 * <ul>
 * <li><b>Metadata:</b> Name, description, and optional fields
 * <li><b>Content:</b> System prompt with instructions
 * <li><b>Root Files:</b> Files in the skill's root directory (optional)
 * <li><b>Scripts:</b> Executable scripts (optional)
 * <li><b>References:</b> Additional documentation (optional)
 * <li><b>Assets:</b> Static resources like templates, images, data files (optional)
 * </ul>
 *
 * <p>
 * Skills follow the Agent Skills standard (https://agentskills.io/).
 *
 * <p>
 * Immutable and thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SkillMetadata metadata = SkillMetadata.builder().name("alert-analysis")
 *             .description("Analyzes alerts from monitoring systems").build();
 *
 *     SkillContent content = SkillContent.of("# Alert Analysis\n\n...");
 *
 *     Skill skill = Skill.builder().name("alert-analysis").metadata(metadata).content(content)
 *             .putScript("query_metrics.py", "scripts/query_metrics.py")
 *             .putReference("patterns.md", "references/patterns.md").putAsset("loader.json", "assets/loader.json")
 *             .build();
 * }
 * </pre>
 */
public final class Skill {

    /**
     * Creates a new builder.
     *
     * @return A new builder instance (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String name;
    private final SkillMetadata metadata;
    private final SkillContent content;
    private final Map<String, String> rootFiles; // filename -> VFS full path
    private final Map<String, String> scripts; // filename -> VFS full path
    private final Map<String, String> references; // filename -> VFS full path
    private final Map<String, String> assets; // filename -> VFS full path
    private final Map<String, String> files; // relative path (from skill dir) -> VFS full path; excludes SKILL.md
    private final String baseDir; // VFS directory of the skill; null when not loaded from a directory-backed repository

    private Skill(Builder builder) {
        name = Objects.requireNonNull(builder.name, "Name cannot be null");
        metadata = Objects.requireNonNull(builder.metadata, "Metadata cannot be null");
        content = Objects.requireNonNull(builder.content, "Content cannot be null");
        rootFiles = builder.rootFiles.isEmpty() ? Map.of() : Map.copyOf(builder.rootFiles);
        scripts = builder.scripts.isEmpty() ? Map.of() : Map.copyOf(builder.scripts);
        references = builder.references.isEmpty() ? Map.of() : Map.copyOf(builder.references);
        assets = builder.assets.isEmpty() ? Map.of() : Map.copyOf(builder.assets);
        files = builder.files.isEmpty() ? Map.of() : Map.copyOf(builder.files);
        baseDir = builder.baseDir;

        // Validate that metadata name matches skill name
        if (!name.equals(metadata.getName())) {
            throw new IllegalArgumentException(
                    String.format("Skill name '%s' does not match metadata name '%s'", name, metadata.getName()));
        }
    }

    /**
     * Gets the skill name.
     *
     * @return The skill name (never null)
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the skill metadata.
     *
     * @return The skill metadata (never null)
     */
    public SkillMetadata getMetadata() {
        return metadata;
    }

    /**
     * Gets the skill content.
     *
     * @return The skill content (never null)
     */
    public SkillContent getContent() {
        return content;
    }

    /**
     * Gets the root files map.
     *
     * @return Unmodifiable map of root files (never null, may be empty)
     */
    public Map<String, String> getRootFiles() {
        return rootFiles;
    }

    /**
     * Gets the scripts map.
     *
     * @return Unmodifiable map of scripts (never null, may be empty)
     */
    public Map<String, String> getScripts() {
        return scripts;
    }

    /**
     * Gets the references map.
     *
     * @return Unmodifiable map of references (never null, may be empty)
     */
    public Map<String, String> getReferences() {
        return references;
    }

    /**
     * Gets the assets map.
     *
     * @return Unmodifiable map of assets (never null, may be empty)
     */
    public Map<String, String> getAssets() {
        return assets;
    }

    /**
     * Gets the comprehensive file map covering <b>every</b> bundled file under the skill directory (excluding
     * {@code SKILL.md}), keyed by the file's path relative to the skill directory.
     *
     * <p>
     * Unlike {@link #getScripts()}, {@link #getReferences()}, and {@link #getAssets()} — which only cover the three
     * conventional sub-directories — this map also includes files in arbitrary sub-directories such as
     * {@code templates/}. It is populated by directory-backed repositories; classpath-only skills that have not been
     * materialized leave it empty.
     *
     * @return Unmodifiable map of relative path to VFS full path (never null, may be empty)
     */
    public Map<String, String> getFiles() {
        return files;
    }

    /**
     * Gets the skill's base directory on the virtual filesystem.
     *
     * <p>
     * When present, this is the authoritative anchor for resolving the skill's own relative file references (for
     * example via the {@code ${AIMON_SKILL_DIR}} render variable). It is populated by directory-backed repositories;
     * skills assembled in-memory or loaded from a classpath repository without materialization return empty.
     *
     * @return The skill base directory, or empty if not applicable
     */
    public Optional<String> getBaseDir() {
        return Optional.ofNullable(baseDir);
    }

    /**
     * Checks if this skill has tools restrictions.
     *
     * @return true if the skill has allowed-tools specified, false otherwise
     */
    public boolean hasToolRestrictions() {
        return metadata.hasToolRestrictions();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final Skill skill = (Skill) o;
        return Objects.equals(name, skill.name) && Objects.equals(metadata, skill.metadata)
                && Objects.equals(content, skill.content) && Objects.equals(rootFiles, skill.rootFiles)
                && Objects.equals(scripts, skill.scripts) && Objects.equals(references, skill.references)
                && Objects.equals(assets, skill.assets) && Objects.equals(files, skill.files)
                && Objects.equals(baseDir, skill.baseDir);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, metadata, content, rootFiles, scripts, references, assets, files, baseDir);
    }

    @Override
    public String toString() {
        return "Skill{" + "name='" + name + '\'' + ", metadata=" + metadata + ", baseDir='" + baseDir + '\''
                + ", rootFiles=" + rootFiles.keySet() + ", scripts=" + scripts.keySet() + ", references="
                + references.keySet() + ", assets=" + assets.keySet() + ", files=" + files.keySet() + '}';
    }

    /** Builder for Skill. */
    public static final class Builder {
        private String name;
        private SkillMetadata metadata;
        private SkillContent content;
        private Map<String, String> rootFiles = Map.of();
        private Map<String, String> scripts = Map.of();
        private Map<String, String> references = Map.of();
        private Map<String, String> assets = Map.of();
        private Map<String, String> files = Map.of();
        private String baseDir;

        private Builder() {
        }

        /**
         * Sets the skill name (required).
         *
         * @param name
         *            The skill name (must not be null)
         * @return This builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the skill metadata (required).
         *
         * @param metadata
         *            The skill metadata (must not be null)
         * @return This builder
         */
        public Builder metadata(SkillMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Sets the skill content (required).
         *
         * @param content
         *            The skill content (must not be null)
         * @return This builder
         */
        public Builder content(SkillContent content) {
            this.content = content;
            return this;
        }

        /**
         * Sets the root files map.
         *
         * @param rootFiles
         *            The root files map (must not be null)
         * @return This builder
         */
        public Builder rootFiles(Map<String, String> rootFiles) {
            this.rootFiles = Objects.requireNonNull(rootFiles, "Root files cannot be null");
            return this;
        }

        /**
         * Adds a root file.
         *
         * @param filename
         *            The root file filename (must not be null)
         * @param path
         *            The relative path to the root file (must not be null)
         * @return This builder
         */
        public Builder putRootFile(String filename, String path) {
            Objects.requireNonNull(filename, "Filename cannot be null");
            Objects.requireNonNull(path, "Path cannot be null");
            if (rootFiles.isEmpty()) {
                rootFiles = new HashMap<>();
            }
            if (!(rootFiles instanceof HashMap)) {
                rootFiles = new HashMap<>(rootFiles);
            }
            rootFiles.put(filename, path);
            return this;
        }

        /**
         * Sets the scripts map.
         *
         * @param scripts
         *            The scripts map (must not be null)
         * @return This builder
         */
        public Builder scripts(Map<String, String> scripts) {
            this.scripts = Objects.requireNonNull(scripts, "Scripts cannot be null");
            return this;
        }

        /**
         * Adds a script.
         *
         * @param filename
         *            The script filename (must not be null)
         * @param path
         *            The relative path to the script (must not be null)
         * @return This builder
         */
        public Builder putScript(String filename, String path) {
            Objects.requireNonNull(filename, "Filename cannot be null");
            Objects.requireNonNull(path, "Path cannot be null");
            if (scripts.isEmpty()) {
                scripts = new HashMap<>();
            }
            if (!(scripts instanceof HashMap)) {
                scripts = new HashMap<>(scripts);
            }
            scripts.put(filename, path);
            return this;
        }

        /**
         * Sets the references map.
         *
         * @param references
         *            The references map (must not be null)
         * @return This builder
         */
        public Builder references(Map<String, String> references) {
            this.references = Objects.requireNonNull(references, "References cannot be null");
            return this;
        }

        /**
         * Adds a reference.
         *
         * @param filename
         *            The reference filename (must not be null)
         * @param path
         *            The relative path to the reference (must not be null)
         * @return This builder
         */
        public Builder putReference(String filename, String path) {
            Objects.requireNonNull(filename, "Filename cannot be null");
            Objects.requireNonNull(path, "Path cannot be null");
            if (references.isEmpty()) {
                references = new HashMap<>();
            }
            if (!(references instanceof HashMap)) {
                references = new HashMap<>(references);
            }
            references.put(filename, path);
            return this;
        }

        /**
         * Sets the assets map.
         *
         * @param assets
         *            The assets map (must not be null)
         * @return This builder
         */
        public Builder assets(Map<String, String> assets) {
            this.assets = Objects.requireNonNull(assets, "Assets cannot be null");
            return this;
        }

        /**
         * Adds an asset.
         *
         * @param filename
         *            The asset filename (must not be null)
         * @param path
         *            The relative path to the asset (must not be null)
         * @return This builder
         */
        public Builder putAsset(String filename, String path) {
            Objects.requireNonNull(filename, "Filename cannot be null");
            Objects.requireNonNull(path, "Path cannot be null");
            if (assets.isEmpty()) {
                assets = new HashMap<>();
            }
            if (!(assets instanceof HashMap)) {
                assets = new HashMap<>(assets);
            }
            assets.put(filename, path);
            return this;
        }

        /**
         * Sets the comprehensive file map (relative path to VFS full path) covering every bundled file under the skill
         * directory except {@code SKILL.md}.
         *
         * @param files
         *            The file map (must not be null)
         * @return This builder
         */
        public Builder files(Map<String, String> files) {
            this.files = Objects.requireNonNull(files, "Files cannot be null");
            return this;
        }

        /**
         * Adds a single bundled file entry.
         *
         * @param relativePath
         *            The file path relative to the skill directory (must not be null)
         * @param path
         *            The VFS full path to the file (must not be null)
         * @return This builder
         */
        public Builder putFile(String relativePath, String path) {
            Objects.requireNonNull(relativePath, "Relative path cannot be null");
            Objects.requireNonNull(path, "Path cannot be null");
            if (files.isEmpty()) {
                files = new HashMap<>();
            }
            if (!(files instanceof HashMap)) {
                files = new HashMap<>(files);
            }
            files.put(relativePath, path);
            return this;
        }

        /**
         * Sets the skill's base directory on the virtual filesystem.
         *
         * @param baseDir
         *            The base directory (may be null)
         * @return This builder
         */
        public Builder baseDir(String baseDir) {
            this.baseDir = baseDir;
            return this;
        }

        /**
         * Builds the Skill.
         *
         * @return A new Skill instance (never null)
         * @throws NullPointerException
         *             if required fields are null
         * @throws IllegalArgumentException
         *             if validation fails
         */
        public Skill build() {
            return new Skill(this);
        }
    }
}
