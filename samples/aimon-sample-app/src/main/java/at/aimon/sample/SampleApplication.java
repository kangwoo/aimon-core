package at.aimon.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/**
 * A Spring Boot application that embeds AIMON through the starter and does nothing else.
 *
 * <p>
 * It exists to be packaged. Every other test in this repository runs against an exploded class path — a
 * {@code build/classes} directory and a list of jars — because that is what Gradle hands a test JVM. A fat jar
 * is a different class path: resources live in nested archives, {@code getResource} returns URLs with a scheme
 * the JDK has never heard of, and a "directory" stops being listable. AIMON reads skills out of exactly those
 * places, so the only honest way to know whether packaging works is to package something and run it.
 *
 * <p>
 * The application therefore has to be small enough to reason about and complete enough to fail honestly. It
 * declares one agent bundle in its own resources, depends on two jars that contribute skills to that bundle
 * without being named anywhere in this code, answers with a scripted model so a turn needs no credentials, and
 * exposes two endpoints that report what the running agent actually has. What it does not do is anything an
 * application would normally do — there is no domain here, and adding one would only dilute the assertion.
 *
 * <p>
 * <b>Why the agent bundle lives in this module and the skills do not.</b> That split is the realistic shape —
 * an integrator writes their own {@code agent.md} and pulls skill packs in as dependencies — and it is also the
 * shape that triggers the loader's development/production branch: {@code agents/sample/agent.md} resolves to a
 * {@code file:} URL when this module runs exploded and to a {@code jar:} URL when it runs packaged, and the two
 * branches reach for different repositories underneath. A sample that shipped everything in one artifact would
 * run both ways and prove neither.
 *
 * <p>
 * <b>The {@code live} profile.</b> Packaging is not the only thing about a server assembly that can be wrong.
 * The starter's defaults deliberately differ from the CLI's — the shell is off, skill approvals deny, the
 * scheduling backend is {@code none} — so the paths behind those switches are the ones a Spring-assembled
 * deployment exercises least and knows least about. {@code --spring.profiles.active=live} turns all three on and
 * swaps in a model that asks for tools, which is the only way to reach them. The default path is left exactly as
 * it was: the packaging tests assert a scripted answer verbatim, and a profile that could move it would be
 * testing itself.
 */
@SpringBootApplication
public class SampleApplication {

    /**
     * Boots the application.
     *
     * @param args
     *            standard Spring Boot arguments; the packaging tests pass {@code --server.port} and
     *            {@code --aimon.workspace.root}
     */
    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }

    /**
     * The scripted model this application answers with.
     *
     * <p>
     * The starter's own {@code LlmClient} is {@code @ConditionalOnMissingBean(LlmClient.class)}, and
     * {@code aimon.llm.provider=none} is the declared way to say "the application defines it". Both halves are
     * needed: the property keeps the starter from insisting on an API key it would never use, and the bean keeps
     * a turn from failing for want of a model.
     *
     * <p>
     * Declared at its concrete type rather than as {@code LlmClient}. The condition matches by type and a
     * {@code ScriptedLlmClient} <em>is</em> one, so the back-off works either way — but declaring it concretely
     * means the introspection endpoint reads the recordings of the very object the executor called, rather than
     * of an object of the same class.
     *
     * <p>
     * Profiled off under {@code live}, where a model that only ever answers in words could not reach a tool at
     * all. The two are mutually exclusive rather than ordered by {@code @Primary}: a context holding both would
     * still hand one of them to the executor, and which one is not something a sample should leave to bean
     * ordering.
     *
     * @return the scripted client
     */
    @Bean
    @Profile("!live")
    public ScriptedLlmClient scriptedLlmClient() {
        return new ScriptedLlmClient();
    }

    /**
     * The tool-driving model the {@code live} profile answers with.
     *
     * <p>
     * Same arrangement as above and for the same two reasons — {@code aimon.llm.provider=none} keeps the starter
     * from insisting on a credential, and the bean keeps the turn from failing for want of a model. What differs
     * is that this one emits {@code tool_use}, which is what makes the shell, the approval chain and the
     * scheduler reachable at all from an HTTP request.
     *
     * @return the scenario client
     */
    @Bean
    @Profile("live")
    public ScenarioLlmClient scenarioLlmClient() {
        return new ScenarioLlmClient();
    }
}
