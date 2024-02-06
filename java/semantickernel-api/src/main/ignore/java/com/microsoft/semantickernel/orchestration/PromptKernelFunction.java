// Copyright (c) Microsoft. All rights reserved.
package com.microsoft.semantickernel.orchestration;

import com.azure.core.exception.HttpResponseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.microsoft.semantickernel.Kernel;
import com.microsoft.semantickernel.SKBuilders;
import com.microsoft.semantickernel.ai.AIException;
import com.microsoft.semantickernel.orchestration.FunctionNotRegisteredException.ErrorCodes;
import com.microsoft.semantickernel.orchestration.contextvariables.ContextVariableType;
import com.microsoft.semantickernel.semanticfunctions.PromptConfig;
import com.microsoft.semantickernel.semanticfunctions.PromptTemplate;
import com.microsoft.semantickernel.semanticfunctions.PromptTemplateConfig;
import com.microsoft.semantickernel.semanticfunctions.SemanticFunctionConfig;
import com.microsoft.semantickernel.skilldefinition.FunctionView;
import com.microsoft.semantickernel.skilldefinition.KernelSkillsSupplier;
import com.microsoft.semantickernel.skilldefinition.ParameterView;
import com.microsoft.semantickernel.skilldefinition.ReadOnlySkillCollection;
import com.microsoft.semantickernel.templateengine.handlebars.HandlebarsPromptTemplate;
import com.microsoft.semantickernel.templateengine.handlebars.HandlebarsPromptTemplateEngine;
import com.microsoft.semantickernel.textcompletion.CompletionKernelFunction;
import com.microsoft.semantickernel.textcompletion.CompletionRequestSettings;
import com.microsoft.semantickernel.textcompletion.TextCompletion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/// <summary>
/// Standard Semantic Kernel callable function.
/// SKFunction is used to extend one C# <see cref="Delegate"/>, <see cref="Func{T, TResult}"/>, <see
// cref="Action"/>,
/// with additional methods required by the kernel.
/// </summary>

public class PromptKernelFunction extends DefaultSemanticKernelFunction
    implements CompletionKernelFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromptKernelFunction.class);

    private final SemanticFunctionConfig functionConfig;
    private SKSemanticAsyncTask<SKContext> function;
    private final CompletionRequestSettings requestSettings;

    @Nullable
    private DefaultTextCompletionSupplier aiService;

    public PromptKernelFunction(
        List<ParameterView> parameters,
        String skillName,
        String functionName,
        String description,
        CompletionRequestSettings requestSettings,
        SemanticFunctionConfig functionConfig,
        @Nullable KernelSkillsSupplier kernelSkillsSupplier) {
        super(parameters, skillName, functionName, description, kernelSkillsSupplier);
        // TODO
        // Verify.NotNull(delegateFunction, "The function delegate is empty");
        // Verify.ValidSkillName(skillName);
        // Verify.ValidFunctionName(functionName);
        // Verify.ParametersUniqueness(parameters);
        this.requestSettings = requestSettings;
        this.functionConfig = functionConfig;
    }

    /*
    /// <inheritdoc/>
    public string Name { get; }

    /// <inheritdoc/>
    public string SkillName { get; }

    /// <inheritdoc/>
    public string Description { get; }

    /// <inheritdoc/>
    public bool IsSemantic { get; }

    /// <inheritdoc/>
    public CompleteRequestSettings RequestSettings
    {
        get { return this._aiRequestSettings; }
    }

    /// <summary>
    /// List of function parameters
    /// </summary>
    public IList<ParameterView> Parameters { get; }

    /// <summary>
    /// Create a native function instance, wrapping a native object method
    /// </summary>
    /// <param name="methodContainerInstance">Object containing the method to invoke</param>
    /// <param name="methodSignature">Signature of the method to invoke</param>
    /// <param name="skillName">SK skill name</param>
    /// <param name="log">Application logger</param>
    /// <returns>SK function instance</returns>
    */

    private SKContext buildContext() {
        assertSkillSupplierRegistered();
        return SKBuilders.context()
            .withVariables(SKBuilders.variables().build())
            .withSkills(getSkillsSupplier() == null ? null : getSkillsSupplier().get())
            .build();
    }

    /**
     * Method to aggregate partitioned results of a semantic function
     *
     * @param partitionedInput Input to aggregate
     * @param contextIn        Semantic Kernel context
     * @return Aggregated results
     */
    @Override
    public Mono<SKContext> aggregatePartitionedResultsAsync(
        List<String> partitionedInput, @Nullable SKContext contextIn) {

        SKContext context;
        if (contextIn == null) {
            context = buildContext();
        } else {
            context = contextIn;
        }

        // Function that takes the current context, updates it with the latest input and invokes the
        // function
        BiFunction<Flux<SKContext>, String, Flux<SKContext>> executeNextChunk =
            (contextInput, input) ->
                contextInput.concatMap(
                    newContext -> {
                        SKContext updated = newContext.copy().update(input);
                        return invokeAsync(updated, null);
                    });

        Flux<SKContext> results =
            Flux.fromIterable(partitionedInput)
                .scanWith(() -> Flux.just(context), executeNextChunk)
                .skip(1) // Skip the first element, which is the initial context
                .concatMap(it -> it);

        // TODO: fix
        return results.map(
                result -> {
                    return result.getResult().toPromptString();
                })
            .collect(Collectors.joining("\n"))
            .map(context::update);
    }

    // Run the semantic function
    @Override
    protected Mono<SKContext> invokeAsyncInternal(SKContext context, @Nullable Object settings) {
        // TODO
        // this.VerifyIsSemantic();
        // this.ensureContextHasSkills(context);

        if (function == null) {
            throw new FunctionNotRegisteredException(
                ErrorCodes.FUNCTION_NOT_REGISTERED, this.getName());
        }

        if (settings == null) {
            settings = this.requestSettings;
        }

        if (this.aiService.get() == null) {
            throw new IllegalStateException("Failed to initialise aiService");
        }

        // TODO: 1.0 fix properly using settings object
        CompletionRequestSettings finalSettings = new CompletionRequestSettings(0.9, 0, 0, 0, 1000);

        return function.run(this.aiService.get(), finalSettings, context)
            .map(
                result -> {
                    return context.update(result.getVariables());
                });
    }

    @Override
    public void registerOnKernel(Kernel kernel) {
        this.function =
            (TextCompletion client,
                CompletionRequestSettings requestSettings,
                SKContext contextInput) -> {
                SKContext context = contextInput.copy();
                // TODO
                // Verify.NotNull(client, "AI LLM backed is empty");

                PromptTemplate func = functionConfig.getTemplate();

                return func.renderAsync(context)
                    .flatMap(
                        prompt ->
                            performCompletionRequest(
                                client, requestSettings, prompt, context))
                    .doOnError(
                        ex -> {
                            LOGGER.warn(
                                "Something went wrong while rendering the semantic"
                                    + " function or while executing the text"
                                    + " completion. Function: {}.{}. Error: {}",
                                getSkillName(),
                                getName(),
                                ex.getMessage());

                            // Common message when you attempt to send text completion
                            // requests to a chat completion model:
                            //    "logprobs, best_of and echo parameters are not
                            // available on gpt-35-turbo model"
                            if (ex instanceof HttpResponseException
                                && ((HttpResponseException) ex)
                                .getResponse()
                                .getStatusCode()
                                == 400
                                && ex.getMessage()
                                .contains(
                                    "parameters are not available"
                                        + " on")) {
                                LOGGER.warn(
                                    "This error indicates that you have attempted"
                                        + " to use a chat completion model in a"
                                        + " text completion service. Try using a"
                                        + " chat completion service instead when"
                                        + " building your kernel, for instance when"
                                        + " building your service use"
                                        + " SKBuilders.chatCompletion() rather than"
                                        + " SKBuilders.textCompletionService().");
                            }
                        });
            };

        this.setSkillsSupplier(kernel::getSkills);
        this.aiService = () -> kernel.getService(null, TextCompletion.class);
    }

    private static Mono<SKContext> performCompletionRequest(
        TextCompletion client,
        CompletionRequestSettings requestSettings,
        String prompt,
        SKContext context) {

        LOGGER.debug("RENDERED PROMPT: \n{}", prompt);

        switch (client.defaultCompletionType()) {
            case NON_STREAMING:
                return client.completeAsync(prompt, requestSettings)
                    .single()
                    .map(completion -> context.update(completion.get(0)));

            case STREAMING:
            default:
                return client.completeStreamAsync(prompt, requestSettings)
                    .filter(completion -> !completion.isEmpty())
                    .take(1)
                    .single()
                    .map(context::update);
        }
    }

    private static String randomFunctionName() {
        return "func" + UUID.randomUUID();
    }

    @Override
    public FunctionView describe() {
        return new FunctionView(
            super.getName(),
            super.getSkillName(),
            super.getDescription(),
            super.getParametersView(),
            true,
            false);
    }

    @Override
    public <T> Flux<ContextVariable<T>> invokeStreamingAsync(Kernel kernel,
        @Nullable KernelArguments arguments, ContextVariableType<T> variableType) {
        return null;
    }

    @Override
    public <T> Mono<ContextVariable<T>> invokeAsync(Kernel kernel,
        @Nullable KernelArguments arguments, ContextVariableType<T> variableType) {
        return null;
    }






    public static class Builder implements CompletionKernelFunction.Builder {

        private Kernel kernel;
        @Nullable
        private String promptTemplate = null;
        @Nullable
        private String functionName = null;
        @Nullable
        private String skillName = null;
        @Nullable
        private String description = null;
        private PromptConfig.CompletionConfig completionConfig =
            new PromptConfig.CompletionConfig();
        @Nullable
        private SemanticFunctionConfig functionConfig = null;
        @Nullable
        private PromptConfig promptConfig = null;

        @Override
        public CompletionKernelFunction build() {
            if (kernel == null) {
                throw new AIException(
                    AIException.ErrorCodes.INVALID_CONFIGURATION,
                    "Called builder to create a function without setting the kernel");
            }

            if (functionName == null) {
                functionName = randomFunctionName();
            }

            // Verify.NotNull(functionConfig, "Function configuration is empty");
            if (skillName == null) {
                skillName = ReadOnlySkillCollection.GlobalSkill;
            }

            if (functionConfig == null) {

                if (description == null) {
                    description = "Generic function, unknown purpose";
                }

                if (promptTemplate == null) {
                    throw new AIException(
                        AIException.ErrorCodes.INVALID_CONFIGURATION,
                        "Must set prompt template before building");
                }

                if (promptConfig == null) {
                    promptConfig =
                        new PromptConfig(description, "completion", completionConfig);
                }

                PromptTemplate template =
                    SKBuilders.promptTemplate()
                        .withPromptTemplateConfig(promptConfig)
                        .withPromptTemplate(promptTemplate)
                        .withPromptTemplateEngine(kernel.getPromptTemplateEngine())
                        .build();

                // Prepare lambda wrapping AI logic
                functionConfig = new SemanticFunctionConfig(promptConfig, template);
            }

            CompletionRequestSettings requestSettings =
                CompletionRequestSettings.fromCompletionConfig(
                    functionConfig.getConfig().getCompletionConfig());

            PromptTemplate promptTemplate = functionConfig.getTemplate();

            DefaultCompletionKernelFunction function =
                new DefaultCompletionKernelFunction(
                    promptTemplate.getParameters(),
                    skillName,
                    functionName,
                    functionConfig.getConfig().getDescription(),
                    requestSettings,
                    functionConfig,
                    null);

            kernel.registerSemanticFunction(function);
            return function;
        }

        @Override
        public CompletionKernelFunction.Builder withKernel(Kernel kernel) {
            this.kernel = kernel;
            return this;
        }

        @Override
        public CompletionKernelFunction.Builder withPromptTemplate(String promptTemplate) {
            this.promptTemplate = promptTemplate;
            return this;
        }

        @Override
        public CompletionKernelFunction.Builder withPromptTemplateConfig(
            PromptConfig promptConfig) {
            this.promptConfig = promptConfig;
            return this;
        }

        @Override
        public CompletionKernelFunction.Builder withCompletionConfig(
            PromptConfig.CompletionConfig completionConfig) {
            this.completionConfig = completionConfig;
            return this;
        }

        @Override
        public CompletionKernelFunction.Builder withSemanticFunctionConfig(
            SemanticFunctionConfig functionConfig) {
            this.functionConfig = functionConfig;
            return this;
        }

        @Override
        public CompletionKernelFunction.Builder withSkillName(@Nullable String skillName) {
            this.skillName = skillName;
            return this;
        }

        @Override
        public CompletionKernelFunction.Builder withFunctionName(@Nullable String functionName) {
            this.functionName = functionName;
            return this;
        }

        @Override
        public CompletionKernelFunction.Builder withDescription(String description) {
            this.description = description;
            return this;
        }
    }



    public static PromptKernelFunction fromYaml(Path filePath) throws IOException {
        return fromYaml(filePath.toAbsolutePath().toString());
    }

    public static PromptKernelFunction fromYaml(String filePath) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        InputStream inputStream =
            Thread.class.getClassLoader().getResourceAsStream(filePath);

        PromptTemplateConfig functionModel =
            mapper.readValue(inputStream, PromptTemplateConfig.class);

        return new Builder()
            .withName(functionModel.getName())
            .withInputParameters(functionModel.getInputVariables())
            .withPromptTemplate(
                new HandlebarsPromptTemplate(
                    functionModel.getTemplate(), new HandlebarsPromptTemplateEngine()))
            .withPluginName(functionModel.getName()) // TODO: 1.0 add plugin name
            .withExecutionSettings(functionModel.getExecutionSettings())
            .withDescription(functionModel.getDescription())
            .build();
    }
}