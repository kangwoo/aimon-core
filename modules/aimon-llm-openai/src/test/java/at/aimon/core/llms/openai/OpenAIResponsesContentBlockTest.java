package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.services.blocking.ResponseService;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.content.ContentBlock;
import at.aimon.core.llm.content.DocumentContentBlock;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;
import at.aimon.core.llms.openai.exception.MessageConversionException;

/**
 * Content-block parity with {@link OpenAIMessageConverter}, case for case, including both of its throws.
 *
 * <p>
 * <strong>The failure this guards is silent.</strong> {@code UserInputConverter} turns an attached screenshot into an
 * {@code ImageContentBlock} and an attached file into a {@code DocumentContentBlock} on every user turn, so a
 * from-scratch converter that handled only text would emit a text-only input item, the model would answer as though
 * nothing was attached, and the build would stay green. Every row is therefore asserted on the serialised {@code input}
 * array rather than on an accessor that could hide a dropped part.
 *
 * <p>
 * One parity row has <strong>no test, and saying so is part of the claim</strong>: the converter mirrors the Chat
 * path's {@code IllegalArgumentException("Unsupported role: ...")}, but {@code Role} has exactly three constants and
 * all three are handled, so no input reaches that arm. It is written for the same reason the Chat converter writes it
 * — parity kept only where it is currently observable is not parity — and it is protected by review rather than by an
 * assertion. Writing a test that constructs nothing and asserts nothing would be worse than admitting the gap.
 */
@DisplayName("OpenAI Responses - content block parity with the Chat converter")
@ExtendWith(MockitoExtension.class)
class OpenAIResponsesContentBlockTest {
    /**
     * A gpt-5-family reasoning model, meaning nothing more than that. It was {@code gpt-5.6-terra} until that name
     * got a built-in row of its own for its measured ladder; a name with its own row would keep every assertion here
     * green while quietly testing a different row from the one they are about.
     */
    private static final String A_REASONING_MODEL = "gpt-5-mini";

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    @Mock
    private OpenAIClient mockOpenAIClient;

    @Mock
    private ResponseService mockResponseService;

    private JsonNode inputFor(Message message) {
        lenient().when(mockOpenAIClient.responses()).thenReturn(mockResponseService);
        when(mockResponseService.create(any(ResponseCreateParams.class))).thenReturn(emptyResponse());
        final OpenAILlmClient client = new OpenAILlmClient(
                OpenAIConfig.builder().apiKey("k").model(A_REASONING_MODEL).build(), mockOpenAIClient);

        client.sendMessage("sys", List.of(message), List.of(), LlmModel.builder().build());

        final ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(mockResponseService).create(captor.capture());
        return ResponsesFixtures.bodyTreeOf(captor.getValue()).get("input");
    }

    private void expectConversionFailure(Message message, String messageFragment) {
        lenient().when(mockOpenAIClient.responses()).thenReturn(mockResponseService);
        final OpenAILlmClient client = new OpenAILlmClient(
                OpenAIConfig.builder().apiKey("k").model(A_REASONING_MODEL).build(), mockOpenAIClient);

        assertThatThrownBy(() -> client.sendMessage("sys", List.of(message), List.of(), LlmModel.builder().build()))
                .isInstanceOf(MessageConversionException.class).hasMessageContaining(messageFragment);
    }

    private static Response emptyResponse() {
        return ResponsesFixtures.response("{\"id\":\"r\",\"created_at\":1,\"model\":\"m\",\"object\":\"response\","
                + "\"parallel_tool_calls\":true,\"tool_choice\":\"auto\",\"tools\":[],\"status\":\"completed\","
                + "\"output\":[]}");
    }

    @Test
    @DisplayName("a base64 image reaches input_image with the same data: URL the Chat converter builds")
    void base64ImageBecomesAnInputImagePart() {
        // The row the earlier design revision would have shipped without: a user attaching a screenshot on a
        // Responses-routed session, silently answered as though nothing was attached.
        final Message message = Message
                .user(List.of(TextContentBlock.of("what is this?"), ImageContentBlock.ofBase64(PNG, "image/png")));

        final JsonNode content = inputFor(message).get(0).get("content");

        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("type").asText()).isEqualTo("input_text");
        assertThat(content.get(0).get("text").asText()).isEqualTo("what is this?");
        assertThat(content.get(1).get("type").asText()).isEqualTo("input_image");
        assertThat(content.get(1).get("image_url").asText())
                .isEqualTo("data:image/png;base64," + Base64.getEncoder().encodeToString(PNG));
        // detail is a required field on ResponseInputImage's builder, and AUTO is what the Chat path effectively
        // sends by omitting the field. A converter that picked HIGH or LOW would change what the message means.
        assertThat(content.get(1).get("detail").asText()).isEqualTo("auto");
    }

    @Test
    @DisplayName("a URL image reaches input_image with the URL unchanged")
    void urlImageBecomesAnInputImagePart() {
        final Message message = Message
                .user(List.of(ImageContentBlock.ofUrl("https://example.com/pic.jpeg", "image/jpeg")));

        final JsonNode content = inputFor(message).get(0).get("content");

        assertThat(content.get(0).get("type").asText()).isEqualTo("input_image");
        assertThat(content.get(0).get("image_url").asText()).isEqualTo("https://example.com/pic.jpeg");
        assertThat(content.get(0).get("detail").asText()).isEqualTo("auto");
    }

    @Test
    @DisplayName("a text-based document is inlined as input_text WITH the [File: ...] header")
    void namedTextDocumentIsInlinedWithItsHeader() {
        final Message message = Message.user(List.of(
                DocumentContentBlock.of("report body".getBytes(StandardCharsets.UTF_8), "text/plain", "report.txt")));

        final JsonNode content = inputFor(message).get(0).get("content");

        assertThat(content.get(0).get("type").asText()).isEqualTo("input_text");
        assertThat(content.get(0).get("text").asText()).isEqualTo("[File: report.txt (text/plain)]\nreport body");
    }

    @Test
    @DisplayName("a text-based document with no file name is inlined WITHOUT a header")
    void unnamedTextDocumentIsInlinedHeaderLess() {
        // Split from the row above because "the identical text" was true for only half the inputs: the Chat
        // converter guards the prefix on getFileName() != null, so a converter that always writes the header passes
        // a test that only ever supplies a name.
        final Message message = Message
                .user(List.of(DocumentContentBlock.of("body only".getBytes(StandardCharsets.UTF_8), "text/plain")));

        final JsonNode content = inputFor(message).get(0).get("content");

        assertThat(content.get(0).get("text").asText()).isEqualTo("body only");
        assertThat(content.get(0).get("text").asText()).doesNotContain("[File:");
    }

    @Test
    @DisplayName("a non-text document throws the same MessageConversionException as the Chat path")
    void nonTextDocumentThrowsTheSameWayAsChat() {
        // The Responses API does have a native input_file slot. Using it is deliberately deferred: it would make the
        // same Message mean different things on the two endpoints and claim a capability no live call has verified.
        // Parity includes the throw.
        expectConversionFailure(
                Message.user(List.of(DocumentContentBlock.of(new byte[]{1, 2}, "application/pdf", "report.pdf"))),
                "does not support document content blocks natively");
    }

    @Test
    @DisplayName("an unknown content block type throws the same MessageConversionException as the Chat path")
    void unknownBlockTypeThrowsTheSameWayAsChat() {
        final ContentBlock unknown = new ContentBlock() {
            @Override
            public String getType() {
                return "custom";
            }

            @Override
            public String asText() {
                return "custom";
            }
        };

        expectConversionFailure(Message.user(List.of(unknown)), "Unsupported content block type");
    }

    @Test
    @DisplayName("a text-only user message uses the same multimodal carrier, so there is no second shape")
    void textOnlyUserMessageUsesTheSameCarrier() {
        final JsonNode input = inputFor(Message.user("plain text"));

        assertThat(input.get(0).get("role").asText()).isEqualTo("user");
        assertThat(input.get(0).get("content")).hasSize(1);
        assertThat(input.get(0).get("content").get(0).get("text").asText()).isEqualTo("plain text");
    }

}
