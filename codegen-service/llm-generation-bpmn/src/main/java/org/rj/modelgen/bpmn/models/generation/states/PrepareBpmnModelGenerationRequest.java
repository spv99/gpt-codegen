package org.rj.modelgen.bpmn.models.generation.states;

import org.rj.modelgen.bpmn.exception.BpmnGenerationException;
import org.rj.modelgen.bpmn.llm.context.provider.impl.ConstrainedBpmnGenerationContextProvider;
import org.rj.modelgen.bpmn.models.generation.context.BpmnGenerationPromptGenerator;
import org.rj.modelgen.bpmn.models.generation.context.BpmnGenerationPromptPlaceholders;
import org.rj.modelgen.bpmn.models.generation.context.BpmnGenerationPromptType;
import org.rj.modelgen.bpmn.models.generation.signals.BpmnGenerationSignals;
import org.rj.modelgen.bpmn.models.generation.signals.LlmModelRequestPreparedSuccessfully;
import org.rj.modelgen.llm.context.Context;
import org.rj.modelgen.llm.context.ContextEntry;
import org.rj.modelgen.llm.context.provider.ContextProvider;
import org.rj.modelgen.llm.prompt.PromptSubstitution;
import org.rj.modelgen.llm.schema.ModelSchema;
import org.rj.modelgen.llm.state.ModelInterfaceSignal;
import org.rj.modelgen.llm.state.ModelInterfaceState;
import org.rj.modelgen.llm.statemodel.data.common.StandardModelData;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;


public class PrepareBpmnModelGenerationRequest extends ModelInterfaceState {
    private final ModelSchema modelSchema;
    private final BpmnGenerationPromptGenerator promptGenerator;
    private final ContextProvider contextProvider;

    public PrepareBpmnModelGenerationRequest(ModelSchema modelSchema, BpmnGenerationPromptGenerator promptGenerator) {
        super(PrepareBpmnModelGenerationRequest.class);
        this.modelSchema = modelSchema;
        this.promptGenerator = promptGenerator;

        this.contextProvider = new ConstrainedBpmnGenerationContextProvider(promptGenerator);
    }

    @Override
    public String getDescription() {
        return "Prepare BPMN model generation request";
    }

    @Override
    protected Mono<ModelInterfaceSignal> invokeAction(ModelInterfaceSignal input) {
        final Context context = getPayload().getOrElse(StandardModelData.Context, contextProvider::newContext);
        final String request = getPayload().getOrThrow(StandardModelData.Request, () -> new BpmnGenerationException("No valid request provided"));
        final String sessionId = getPayload().getOrThrow(StandardModelData.SessionId, () -> new BpmnGenerationException("No valid session ID for request"));

        final var prompt = promptGenerator.getPrompt(BpmnGenerationPromptType.Generate, List.of(
                new PromptSubstitution(BpmnGenerationPromptPlaceholders.SCHEMA_CONTENT, modelSchema.getSchemaContent()),
                new PromptSubstitution(BpmnGenerationPromptPlaceholders.CURRENT_STATE, context.getLatestModelEntry()
                        .orElseGet(() -> ContextEntry.forModel("{}")).getContent()),
                new PromptSubstitution(BpmnGenerationPromptPlaceholders.PROMPT, request)))

                .orElseThrow(() -> new BpmnGenerationException("Could not generate new prompt"));

        final var newContext = contextProvider.withPrompt(context, prompt);
        getModelInterface().getOrCreateSession(sessionId).replaceContext(newContext);

        return outboundSignal(BpmnGenerationSignals.SubmitRequestToLlm)
                .withPayloadData(StandardModelData.Context, newContext)
                .mono();
    }
}
