package cn.bugstack.ai.test.api.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.models.springai.SpringAI;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

public class ParallelAgentTest {

    private static final String APP_NAME = "parallel_research_app";
    private static final String USER_ID = "research_user_01";

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

        // --- 1. Define Researcher Sub-Agents (to run in parallel) ---
        // Researcher 1: Renewable Energy
        LlmAgent researcherAgent1 = LlmAgent.builder()
                .name("RenewableEnergyResearcher")
                .model(new SpringAI(chatModel))
                .instruction("""
                     You are an AI Research Assistant specializing in energy.
                     Research the latest advancements in 'renewable energy sources'.
                     Use the Google Search tool provided.
                     Summarize your key findings concisely (1-2 sentences).
                     Output *only* the summary.
                     """)
                .description("Researches renewable energy sources.")
                .outputKey("renewable_energy_result") // Store result in state
                .build();

        LlmAgent researcherAgent2 = LlmAgent.builder()
                .name("EVResearcher")
                .model(new SpringAI(chatModel))
                .instruction("""
                     You are an AI Research Assistant specializing in transportation.
                     Research the latest developments in 'electric vehicle technology'.
                     Use the Google Search tool provided.
                     Summarize your key findings concisely (1-2 sentences).
                     Output *only* the summary.
                     """)
                .description("Researches electric vehicle technology.")
                .outputKey("ev_technology_result") // Store result in state
                .build();

        LlmAgent researcherAgent3 = LlmAgent.builder()
                .name("CarbonCaptureResearcher")
                .model(new SpringAI(chatModel))
                .instruction("""
                     You are an AI Research Assistant specializing in climate solutions.
                     Research the current state of 'carbon capture methods'.
                     Use the Google Search tool provided.
                     Summarize your key findings concisely (1-2 sentences).
                     Output *only* the summary.
                     """)
                .description("Researches carbon capture methods.")
                .outputKey("carbon_capture_result") // Store result in state
                .build();

        ParallelAgent parallelResearchAgent =
                ParallelAgent.builder()
                        .name("ParallelWebResearchAgent")
                        .subAgents(researcherAgent1, researcherAgent2, researcherAgent3)
                        .description("Runs multiple research agents in parallel to gather information.")
                        .build();

        LlmAgent mergerAgent =
                LlmAgent.builder()
                        .name("SynthesisAgent")
                        .model(new SpringAI(chatModel))
                        .instruction(
                                """
                                      You are an AI Assistant responsible for combining research findings into a structured report.
                                      Your primary task is to synthesize the following research summaries, clearly attributing findings to their source areas. Structure your response using headings for each topic. Ensure the report is coherent and integrates the key points smoothly.
                                      **Crucially: Your entire response MUST be grounded *exclusively* on the information provided in the 'Input Summaries' below. Do NOT add any external knowledge, facts, or details not present in these specific summaries.**
                                      **Input Summaries:**
               
                                      *   **Renewable Energy:**
                                          {renewable_energy_result}
               
                                      *   **Electric Vehicles:**
                                          {ev_technology_result}
               
                                      *   **Carbon Capture:**
                                          {carbon_capture_result}
               
                                      **Output Format:**
               
                                      ## Summary of Recent Sustainable Technology Advancements
               
                                      ### Renewable Energy Findings
                                      (Based on RenewableEnergyResearcher's findings)
                                      [Synthesize and elaborate *only* on the renewable energy input summary provided above.]
               
                                      ### Electric Vehicle Findings
                                      (Based on EVResearcher's findings)
                                      [Synthesize and elaborate *only* on the EV input summary provided above.]
               
                                      ### Carbon Capture Findings
                                      (Based on CarbonCaptureResearcher's findings)
                                      [Synthesize and elaborate *only* on the carbon capture input summary provided above.]
               
                                      ### Overall Conclusion
                                      [Provide a brief (1-2 sentence) concluding statement that connects *only* the findings presented above.]
               
                                      Output *only* the structured report following this format. Do not include introductory or concluding phrases outside this structure, and strictly adhere to using only the provided input summary content.
                                      """)
                        .description(
                                "Combines research findings from parallel agents into a structured, cited report, strictly grounded on provided inputs.")
                        // No tools needed for merging
                        // No output_key needed here, as its direct response is the final output of the sequence
                        .build();

        SequentialAgent sequentialPipelineAgent =
                SequentialAgent.builder()
                        .name("ResearchAndSynthesisPipeline")
                        // Run parallel research first, then merge
                        .subAgents(parallelResearchAgent, mergerAgent)
                        .description("Coordinates parallel research and synthesizes the results.")
                        .build();


    }

}
