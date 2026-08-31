package at.aimon.session.routing.fixture;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.SubmitRequest;

/**
 * Builders for {@link SubmitRequest} fixtures used across WS-02 tests.
 */
public final class RequestFixtures {

    private RequestFixtures() {
    }

    public static SubmitRequest submit(SessionId id, String agentRef, String input) {
        return SubmitRequest.builder().sessionId(id).agentRef(agentRef).userInput(input)
                .initiator(Principal.user("tester")).build();
    }
}
