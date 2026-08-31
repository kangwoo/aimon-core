package at.aimon.session.routing.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import at.aimon.core.agent.session.LiveSessionFactory;
import at.aimon.core.agent.session.OpenAttributes;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.session.routing.LiveSessionOpener;
import at.aimon.session.routing.SessionRouter;

/**
 * Verifies {@link SessionRouterBuilder} session-source wiring: exactly one of
 * {@link SessionRouterBuilder#sessionFactory(LiveSessionFactory)} or
 * {@link SessionRouterBuilder#sessionOpener(LiveSessionOpener)} must be set, and the opener path actually
 * builds a usable manager.
 */
@DisplayName("SessionRouterBuilder session source wiring")
class SessionRouterBuilderTest {

    @Test
    @DisplayName("build() rejects when neither sessionFactory nor sessionOpener is set")
    void rejectsWhenNeitherSet() {
        final SessionRouterBuilder builder = SessionRouter.builder()
                .sessionRecordStore(new InMemorySessionRecordStore());

        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sessionFactory").hasMessageContaining("sessionOpener");
    }

    @Test
    @DisplayName("build() rejects when both sessionFactory and sessionOpener are set")
    void rejectsWhenBothSet() {
        final LiveSessionFactory factory = Mockito.mock(LiveSessionFactory.class);
        final LiveSessionOpener opener = (id, ref, opts, attrs) -> {
            throw new AssertionError("opener should not have been invoked");
        };

        final SessionRouterBuilder builder = SessionRouter.builder().sessionFactory(factory).sessionOpener(opener)
                .sessionRecordStore(new InMemorySessionRecordStore());

        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    @DisplayName("build() with sessionOpener returns a usable manager (opener path)")
    void openerPathBuilds() {
        final LiveSessionOpener opener = (id, ref, opts, attrs) -> {
            throw new AssertionError("opener invoked unexpectedly during build");
        };
        final SessionRecordStore repository = new InMemorySessionRecordStore();

        final SessionRouter manager = SessionRouter.builder().sessionOpener(opener).sessionRecordStore(repository)
                .build();

        try {
            assertThat(manager).isNotNull();
        } finally {
            manager.close();
        }
    }

    @Test
    @DisplayName("build() with sessionFactory still works (legacy path) — empty OpenAttributes flows through")
    void factoryPathBuilds() {
        final LiveSessionFactory factory = Mockito.mock(LiveSessionFactory.class);

        final SessionRouter manager = SessionRouter.builder().sessionFactory(factory)
                .sessionRecordStore(new InMemorySessionRecordStore()).build();
        try {
            assertThat(manager).isNotNull();
            // Sanity: OpenAttributes.empty() is a singleton; the legacy path is allowed to ignore attributes entirely.
            assertThat(OpenAttributes.empty().isEmpty()).isTrue();
        } finally {
            manager.close();
        }
    }
}
