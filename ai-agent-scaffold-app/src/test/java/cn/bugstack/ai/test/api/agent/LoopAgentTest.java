package cn.bugstack.ai.test.api.agent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.Map;

import static com.google.adk.agents.LlmAgent.IncludeContents.NONE;

public class LoopAgentTest {

    private static final String APP_NAME = "IterativeWritingPipeline";
    private static final String USER_ID = "test_user_456";

    private static final String STATE_CURRENT_DOC = "current_document";
    private static final String STATE_CRITICISM = "criticism";

   /* @Annotations.Schema(
            description =
                    "Call this function ONLY when the critique indicates no further changes are needed,"
                            + " signaling the iterative process should end.")
    public static Map<String, Object> exitLoop(@Annotations.Schema(name = "toolContext") ToolContext toolContext) {
        System.out.printf("[Tool Call] exitLoop triggered by %s \n", toolContext.agentName());
        toolContext.actions().setEscalate(true);

        //  Return empty dict as tools should typically return JSON-serializable output

        return Map.of();
    }*/

    @Annotations.Schema(
            description =
                    "Call this function ONLY when the critique indicates no further changes are needed,"
                            + " signaling the iterative process should end.")
    public static Map<String, Object> exitLoop() {
        //  Return empty dict as tools should typically return JSON-serializable output

        return Map.of();
    }

    public static void main(String[] args) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl("https://apis.itedus.cn")
                .apiKey("sk-wtBOjyNviG9NtbYn7f2fF8A2203048Aa86Be6f0f0b824dB9")
                .completionsPath("v1/chat/completions")
                .embeddingsPath("v1/embeddings")
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4.1")
                        .build())
                .build();

        // STEP 1: Initial Writer Agent (Runs ONCE at the beginning)
        LlmAgent initialWriterAgent =
                LlmAgent.builder()
                        .model(new SpringAI(chatModel))
                        .name("InitialWriterAgent")
                        .description(
                                "Writes the initial document draft based on the topic, aiming for some initial"
                                        + " substance.")
                        .instruction(
                                """
                                    You are a Creative Writing Assistant tasked with starting a story.
                                    Write the *first draft* of a short story (aim for 2-4 sentences).
                                    Base the content *only* on the topic provided below. Try to introduce a specific element (like a character, a setting detail, or a starting action) to make it engaging.
                
                                    Output *only* the story/document text. Do not add introductions or explanations.
                                """)
                        .outputKey(STATE_CURRENT_DOC)
                        .includeContents(NONE)
                        .build();

        // STEP 2a: Critic Agent (Inside the Refinement Loop)
        LlmAgent criticAgentInLoop =
                LlmAgent.builder()
                        .model(new SpringAI(chatModel))
                        .name("CriticAgent")
                        .description(
                                "Reviews the current draft, providing critique if clear improvements are needed,"
                                        + " otherwise signals completion.")
                        .instruction(
                                """
                                    You are a Constructive Critic AI reviewing a short document draft (typically 2-6 sentences). Your goal is balanced feedback.
                
                                    **Document to Review:**
                                    ```
                                    {{current_document}}
                                    ```
                
                                    **Task:**
                                    Review the document for clarity, engagement, and basic coherence according to the initial topic (if known).
                
                                    IF you identify 1-2 *clear and actionable* ways the document could be improved to better capture the topic or enhance reader engagement (e.g., "Needs a stronger opening sentence", "Clarify the character's goal"):
                                    Provide these specific suggestions concisely. Output *only* the critique text.
                
                                    ELSE IF the document is coherent, addresses the topic adequately for its length, and has no glaring errors or obvious omissions:
                                    Respond *exactly* with the phrase "No major issues found." and nothing else. It doesn't need to be perfect, just functionally complete for this stage. Avoid suggesting purely subjective stylistic preferences if the core is sound.
                
                                    Do not add explanations. Output only the critique OR the exact completion phrase.
                                    """)
                        .outputKey(STATE_CRITICISM)
                        .includeContents(NONE)
                        .build();

        // STEP 2b: Refiner/Exiter Agent (Inside the Refinement Loop)
        LlmAgent refinerAgentInLoop =
                LlmAgent.builder()
                        .model(new SpringAI(chatModel))
                        .name("RefinerAgent")
                        .description(
                                "Refines the document based on critique, or calls exitLoop if critique indicates"
                                        + " completion.")
                        .instruction(
                                """
                                    You are a Creative Writing Assistant refining a document based on feedback OR exiting the process.
                                    **Current Document:**
                                    ```
                                    {{current_document}}
                                    ```
                                    **Critique/Suggestions:**
                                    {{criticism}}
                
                                    **Task:**
                                    Analyze the 'Critique/Suggestions'.
                                    IF the critique is *exactly* "No major issues found.":
                                    You MUST call the 'exitLoop' function. Do not output any text.
                                    ELSE (the critique contains actionable feedback):
                                    Carefully apply the suggestions to improve the 'Current Document'. Output *only* the refined document text.
                
                                    Do not add explanations. Either output the refined document OR call the exitLoop function.
                                """)
                        .outputKey(STATE_CURRENT_DOC)
                        .includeContents(NONE)
                        .tools(FunctionTool.create(LoopAgentTest.class, "exitLoop"))
                        .build();

        // STEP 2: Refinement Loop Agent
        LoopAgent refinementLoop =
                LoopAgent.builder()
                        .name("RefinementLoop")
                        .description("Repeatedly refines the document with critique and then exits.")
                        .subAgents(criticAgentInLoop, refinerAgentInLoop)
                        .maxIterations(5)
                        .build();

        // STEP 3: Overall Sequential Pipeline
        SequentialAgent iterativeWriterAgent =
                SequentialAgent.builder()
                        .name(APP_NAME)
                        .description(
                                "Writes an initial document and then iteratively refines it with critique using an"
                                        + " exit tool.")
                        .subAgents(initialWriterAgent, refinementLoop)
                        .build();

        // Create an InMemoryRunner
        InMemoryRunner runner = new InMemoryRunner(iterativeWriterAgent, APP_NAME);
        // InMemoryRunner automatically creates a session service. Create a session using the service
        Session session = runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet();
        Content userMessage = Content.fromParts(Part.fromText("Write a document about a cat"));

        // Run the agent
        Flowable<Event> eventStream = runner.runAsync(USER_ID, session.id(), userMessage);

        // Stream event response
        eventStream.blockingForEach(
                event -> {
                    if (event.finalResponse()) {
                        System.out.println(event.stringifyContent());
                    }
                });
    }

}
