/*************
 * Copyright (c) 2019-2020, The University of California at Berkeley.

 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:

 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.

 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.

 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ***************/
package org.lflang.generator;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IMarker;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.xbase.lib.IterableExtensions;
import org.eclipse.xtext.xbase.lib.IteratorExtensions;

import org.lflang.ASTUtils;
import org.lflang.ErrorReporter;
import org.lflang.FileConfig;
import org.lflang.MainConflictChecker;
import org.lflang.Target;
import org.lflang.TargetConfig;
import org.lflang.TimeUnit;
import org.lflang.TimeValue;
import org.lflang.ast.AstTransformation;
import org.lflang.graph.InstantiationGraph;
import org.lflang.lf.Connection;
import org.lflang.lf.Expression;
import org.lflang.lf.Instantiation;
import org.lflang.lf.LfFactory;
import org.lflang.lf.Mode;

import org.lflang.lf.Reaction;
import org.lflang.lf.Reactor;
import org.lflang.lf.Time;
import org.lflang.validation.AbstractLFValidator;

import com.google.common.base.Objects;
import com.google.common.collect.Iterables;

/**
 * Generator base class for specifying core functionality
 * that all code generators should have.
 *
 * @author Edward A. Lee
 * @author Marten Lohstroh
 * @author Christian Menard
 * @author Matt Weber
 * @author Soroush Bateni
 */
public abstract class GeneratorBase extends AbstractLFValidator {

    ////////////////////////////////////////////
    //// Public fields.

    /**
     * The main (top-level) reactor instance.
     */
    public ReactorInstance main;

    /** An error reporter for reporting any errors or warnings during the code generation */
    public ErrorReporter errorReporter;

    ////////////////////////////////////////////
    //// Protected fields.

    /**
     * The current target configuration.
     */
    protected final TargetConfig targetConfig;

    public TargetConfig getTargetConfig() { return this.targetConfig;}

    public final LFGeneratorContext context;

    /**
     * A factory for compiler commands.
     */
    protected GeneratorCommandFactory commandFactory;

    public GeneratorCommandFactory getCommandFactory() { return commandFactory; }

    /**
     * Definition of the main (top-level) reactor.
     * This is an automatically generated AST node for the top-level
     * reactor.
     */
    protected Instantiation mainDef;
    public Instantiation getMainDef() { return mainDef; }

    /**
     * A list of Reactor definitions in the main resource, including non-main
     * reactors defined in imported resources. These are ordered in the list in
     * such a way that each reactor is preceded by any reactor that it instantiates
     * using a command like `foo = new Foo();`
     */
    protected List<Reactor> reactors = new ArrayList<>();

    /**
     * The set of resources referenced reactor classes reside in.
     */
    protected Set<LFResource> resources = new LinkedHashSet<>(); // FIXME: Why do we need this?

    /**
     * Graph that tracks dependencies between instantiations.
     * This is a graph where each node is a Reactor (not a ReactorInstance)
     * and an arc from Reactor A to Reactor B means that B contains an instance of A, constructed with a statement
     * like `a = new A();`  After creating the graph,
     * sort the reactors in topological order and assign them to the reactors class variable.
     * Hence, after this method returns, `this.reactors` will be a list of Reactors such that any
     * reactor is preceded in the list by reactors that it instantiates.
     */
    protected InstantiationGraph instantiationGraph;

    /**
     * The set of unordered reactions. An unordered reaction is one that does
     * not have any dependency on other reactions in the containing reactor,
     * and where no other reaction in the containing reactor depends on it.
     * There is currently no way in the syntax of LF to make a reaction
     * unordered, deliberately, because it can introduce unexpected
     * nondeterminacy. However, certain automatically generated reactions are
     * known to be safe to be unordered because they do not interact with the
     * state of the containing reactor. To make a reaction unordered, when
     * the Reaction instance is created, add that instance to this set.
     */
    protected Set<Reaction> unorderedReactions = null;

    /**
     * Map from reactions to bank indices
     */
    protected Map<Reaction,Integer> reactionBankIndices = null;

    /**
     * Indicates whether the current Lingua Franca program
     * contains model reactors.
     */
    public boolean hasModalReactors = false;

    /**
     * Indicates whether the program has any deadlines and thus
     * needs to propagate deadlines through the reaction instance graph
     */
    public boolean hasDeadlines = false;

    // //////////////////////////////////////////
    // // Private fields.

    /**
     * A list ot AST transformations to apply before code generation
     */
    private List<AstTransformation> astTransformations = new ArrayList();

    /**
     * Create a new GeneratorBase object.
     */
    public GeneratorBase(LFGeneratorContext context) {
        this.context = context;
        this.targetConfig = context.getTargetConfig();
        this.errorReporter = context.getErrorReporter();
        this.commandFactory = new GeneratorCommandFactory(errorReporter, context.getFileConfig());
    }

    /**
     * Register an AST transformation to be applied to the AST.
     *
     * The transformations will be applied in the order that they are registered in.
     */
    protected void registerTransformation(AstTransformation transformation) {
        astTransformations.add(transformation);
    }

    // //////////////////////////////////////////
    // // Code generation functions to override for a concrete code generator.

    /**
     * If there is a main or federated reactor, then create a synthetic Instantiation
     * for that top-level reactor and set the field mainDef to refer to it.
     */
    private void createMainInstantiation() {
        // Find the main reactor and create an AST node for its instantiation.
        Iterable<EObject> nodes = IteratorExtensions.toIterable(context.getFileConfig().resource.getAllContents());
        for (Reactor reactor : Iterables.filter(nodes, Reactor.class)) {
            if (reactor.isMain()) {
                // Creating a definition for the main reactor because there isn't one.
                this.mainDef = LfFactory.eINSTANCE.createInstantiation();
                this.mainDef.setName(reactor.getName());
                this.mainDef.setReactorClass(reactor);
            }
        }
    }

    /**
     * Generate code from the Lingua Franca model contained by the specified resource.
     *
     * This is the main entry point for code generation. This base class finds all
     * reactor class definitions, including any reactors defined in imported .lf files
     * (except any main reactors in those imported files), and adds them to the
     * {@link GeneratorBase#reactors reactors} list. If errors occur during
     * generation, then a subsequent call to errorsOccurred() will return true.
     * @param resource The resource containing the source code.
     * @param context Context relating to invocation of the code generator.
     * In standalone mode, this object is also used to relay CLI arguments.
     */
    public void doGenerate(Resource resource, LFGeneratorContext context) {

        // FIXME: the signature can be reduced to only take context.
        // The constructor also need not take a file config because this is tied to the context as well.
        cleanIfNeeded(context);

        printInfo(context.getMode());

        // Clear any IDE markers that may have been created by a previous build.
        // Markers mark problems in the Eclipse IDE when running in integrated mode.
        if (errorReporter instanceof EclipseErrorReporter) {
            ((EclipseErrorReporter) errorReporter).clearMarkers();
        }

        ASTUtils.setMainName(context.getFileConfig().resource, context.getFileConfig().name);

        createMainInstantiation();

        // Check if there are any conflicting main reactors elsewhere in the package.
        if (Objects.equal(context.getMode(), LFGeneratorContext.Mode.STANDALONE) && mainDef != null) {
            for (String conflict : new MainConflictChecker(context.getFileConfig()).conflicts) {
                errorReporter.reportError(this.mainDef.getReactorClass(), "Conflicting main reactor in " + conflict);
            }
        }

        // Configure the command factory
        commandFactory.setVerbose();
        if (Objects.equal(context.getMode(), LFGeneratorContext.Mode.STANDALONE) && context.getArgs().containsKey("quiet")) {
            commandFactory.setQuiet();
        }

        // Process target files. Copy each of them into the src-gen dir.
        // FIXME: Should we do this here? This doesn't make sense for federates the way it is
        // done here.
        copyUserFiles(this.targetConfig, context.getFileConfig());

        // Collect reactors and create an instantiation graph.
        // These are needed to figure out which resources we need
        // to validate, which happens in setResources().
        setReactorsAndInstantiationGraph(context.getMode());

        GeneratorUtils.validate(context, context.getFileConfig(), instantiationGraph, errorReporter);
        List<Resource> allResources = GeneratorUtils.getResources(reactors);
        resources.addAll(allResources.stream()  // FIXME: This filter reproduces the behavior of the method it replaces. But why must it be so complicated? Why are we worried about weird corner cases like this?
            .filter(it -> !Objects.equal(it, context.getFileConfig().resource) || mainDef != null && it == mainDef.getReactorClass().eResource())
            .map(it -> GeneratorUtils.getLFResource(it, context.getFileConfig().getSrcGenBasePath(), context, errorReporter))
            .toList()
        );
        GeneratorUtils.accommodatePhysicalActionsIfPresent(
            allResources,
            getTarget().setsKeepAliveOptionAutomatically(),
            targetConfig,
            errorReporter
        );
        // FIXME: Should the GeneratorBase pull in `files` from imported
        // resources?

        for (AstTransformation transformation : astTransformations) {
            transformation.applyTransformation(reactors);
        }

        // Transform connections that reside in mutually exclusive modes and are otherwise conflicting
        // This should be done before creating the instantiation graph
        transformConflictingConnectionsInModalReactors();

        // Invoke these functions a second time because transformations
        // may have introduced new reactors!
        setReactorsAndInstantiationGraph(context.getMode());

        // Check for existence and support of modes
        hasModalReactors = IterableExtensions.exists(reactors, it -> !it.getModes().isEmpty());
        checkModalReactorSupport(false);
        additionalPostProcessingForModes();
    }

    /**
     * Check if a clean was requested from the standalone compiler and perform
     * the clean step.
     */
    protected void cleanIfNeeded(LFGeneratorContext context) {
        if (context.getArgs().containsKey("clean")) {
            try {
                context.getFileConfig().doClean();
            } catch (IOException e) {
                System.err.println("WARNING: IO Error during clean");
            }
        }
    }

    /**
     * Create a new instantiation graph. This is a graph where each node is a Reactor (not a ReactorInstance)
     * and an arc from Reactor A to Reactor B means that B contains an instance of A, constructed with a statement
     * like `a = new A();`  After creating the graph,
     * sort the reactors in topological order and assign them to the reactors class variable.
     * Hence, after this method returns, `this.reactors` will be a list of Reactors such that any
     * reactor is preceded in the list by reactors that it instantiates.
     */
    protected void setReactorsAndInstantiationGraph(LFGeneratorContext.Mode mode) {
        // Build the instantiation graph .
        instantiationGraph = new InstantiationGraph(context.getFileConfig().resource, false);

        // Topologically sort the reactors such that all of a reactor's instantiation dependencies occur earlier in
        // the sorted list of reactors. This helps the code generator output code in the correct order.
        // For example if `reactor Foo {bar = new Bar()}` then the definition of `Bar` has to be generated before
        // the definition of `Foo`.
        reactors = instantiationGraph.nodesInTopologicalOrder();

        // If there is no main reactor or if all reactors in the file need to be validated, then make sure the reactors
        // list includes even reactors that are not instantiated anywhere.
        if (mainDef == null || Objects.equal(mode, LFGeneratorContext.Mode.LSP_MEDIUM)) {
            Iterable<EObject> nodes = IteratorExtensions.toIterable(context.getFileConfig().resource.getAllContents());
            for (Reactor r : IterableExtensions.filter(nodes, Reactor.class)) {
                if (!reactors.contains(r)) {
                    reactors.add(r);
                }
            }
        }
    }

    /**
     * Copy user specific files to the src-gen folder.
     *
     * This should be overridden by the target generators.
     *
     * @param targetConfig The targetConfig to read the `files` from.
     * @param fileConfig The fileConfig used to make the copy and resolve paths.
     */
    protected void copyUserFiles(TargetConfig targetConfig, FileConfig fileConfig) {}

    /**
     * Return true if errors occurred in the last call to doGenerate().
     * This will return true if any of the reportError methods was called.
     * @return True if errors occurred.
     */
    public boolean errorsOccurred() {
        return errorReporter.getErrorsOccurred();
    }

    /*
     * Return the TargetTypes instance associated with this.
     */
    public abstract TargetTypes getTargetTypes();

    /**
     * Mark the reaction unordered. An unordered reaction is one that does not
     * have any dependency on other reactions in the containing reactor, and
     * where no other reaction in the containing reactor depends on it. There
     * is currently no way in the syntax of LF to make a reaction unordered,
     * deliberately, because it can introduce unexpected nondeterminacy.
     * However, certain automatically generated reactions are known to be safe
     * to be unordered because they do not interact with the state of the
     * containing reactor. To make a reaction unordered, when the Reaction
     * instance is created, add that instance to this set.
     * @param reaction The reaction to make unordered.
     */
    public void makeUnordered(Reaction reaction) {
        if (unorderedReactions == null) {
            unorderedReactions = new LinkedHashSet<>();
        }
        unorderedReactions.add(reaction);
    }

    /**
     * Mark the specified reaction to belong to only the specified
     * bank index. This is needed because reactions cannot declare
     * a specific bank index as an effect or trigger. Reactions that
     * send messages between federates, including absent messages,
     * need to be specific to a bank member.
     * @param reaction The reaction.
     * @param bankIndex The bank index, or -1 if there is no bank.
     */
    public void setReactionBankIndex(Reaction reaction, int bankIndex) {
        if (bankIndex < 0) {
            return;
        }
        if (reactionBankIndices == null) {
            reactionBankIndices = new LinkedHashMap<>();
        }
        reactionBankIndices.put(reaction, bankIndex);
    }

    /**
     * Return the reaction bank index.
     * @see #setReactionBankIndex(Reaction reaction, int bankIndex)
     * @param reaction The reaction.
     * @return The reaction bank index, if one has been set, and -1 otherwise.
     */
    public int getReactionBankIndex(Reaction reaction) {
        if (reactionBankIndices == null) return -1;
        if (reactionBankIndices.get(reaction) == null) return -1;
        return reactionBankIndices.get(reaction);
    }

    /**
     * Given a representation of time that may possibly include units, return
     * a string that the target language can recognize as a value. In this base
     * class, if units are given, e.g. "msec", then we convert the units to upper
     * case and return an expression of the form "MSEC(value)". Particular target
     * generators will need to either define functions or macros for each possible
     * time unit or override this method to return something acceptable to the
     * target language.
     * @param time A TimeValue that represents a time.
     * @return A string, such as "MSEC(100)" for 100 milliseconds.
     */
    public static String timeInTargetLanguage(TimeValue time) {
        if (time != null) {
            if (time.unit != null) {
                return cMacroName(time.unit) + "(" + time.getMagnitude() + ")";
            } else {
                return Long.valueOf(time.getMagnitude()).toString();
            }
        }
        return "0"; // FIXME: do this or throw exception?
    }

    // note that this is moved out by #544
    public static String cMacroName(TimeUnit unit) {
        return unit.getCanonicalName().toUpperCase();
    }

    // //////////////////////////////////////////
    // // Protected methods.

    /**
     * Checks whether modal reactors are present and require appropriate code generation.
     * This will set the hasModalReactors variable.
     * @param isSupported indicates if modes are supported by this code generation.
     */
    protected void checkModalReactorSupport(boolean isSupported) {
        if (hasModalReactors && !isSupported) {
            errorReporter.reportError("The currently selected code generation or " +
                                      "target configuration does not support modal reactors!");
        }
    }

    /**
     * Finds and transforms connections into forwarding reactions iff the connections have the same destination as other
     * connections or reaction in mutually exclusive modes.
     */
    private void transformConflictingConnectionsInModalReactors() {
        for (LFResource r : resources) {
            var transform = ASTUtils.findConflictingConnectionsInModalReactors(r.eResource);
            if (!transform.isEmpty()) {
                var factory = LfFactory.eINSTANCE;
                for (Connection connection : transform) {
                    // Currently only simple transformations are supported
                    if (connection.isPhysical() || connection.getDelay() != null || connection.isIterated() ||
                        connection.getLeftPorts().size() > 1 || connection.getRightPorts().size() > 1
                    ) {
                        errorReporter.reportError(connection, "Cannot transform connection in modal reactor. Connection uses currently not supported features.");
                    } else {
                        var reaction = factory.createReaction();
                        ((Mode)connection.eContainer()).getReactions().add(reaction);

                        var sourceRef = connection.getLeftPorts().get(0);
                        var destRef = connection.getRightPorts().get(0);
                        reaction.getTriggers().add(sourceRef);
                        reaction.getEffects().add(destRef);

                        var code = factory.createCode();
                        var source = (sourceRef.getContainer() != null ?
                                sourceRef.getContainer().getName() + "." : "") + sourceRef.getVariable().getName();
                        var dest = (destRef.getContainer() != null ?
                                destRef.getContainer().getName() + "." : "") + destRef.getVariable().getName();
                        code.setBody(getConflictingConnectionsInModalReactorsBody(source, dest));
                        reaction.setCode(code);

                        EcoreUtil.remove(connection);
                    }
                }
            }
        }
    }
    /**
     * Return target code for forwarding reactions iff the connections have the
     * same destination as other connections or reaction in mutually exclusive modes.
     *
     * This method needs to be overridden in target specific code generators that
     * support modal reactors.
     */
    protected String getConflictingConnectionsInModalReactorsBody(String source, String dest) {
        errorReporter.reportError("The currently selected code generation " +
                                  "is missing an implementation for conflicting " +
                                  "transforming connections in modal reactors.");
        return "MODAL MODELS NOT SUPPORTED";
    }

    /**
     * Hook for additional post-processing of the model.
     */
    protected void additionalPostProcessingForModes() {
        // Do nothing
    }

    /**
     * Parsed error message from a compiler is returned here.
     */
    public static class ErrorFileAndLine {
        public String filepath = null;
        public String line = "1";
        public String character = "0";
        public String message = "";
        public boolean isError = true; // false for a warning.

        @Override
        public String toString() {
          return (isError ? "Error" : "Non-error") + " at " + line + ":" + character + " of file " + filepath + ": " + message;
        }
    }

    /**
     * Given a line of text from the output of a compiler, return
     * an instance of ErrorFileAndLine if the line is recognized as
     * the first line of an error message. Otherwise, return null.
     * This base class simply returns null.
     * @param line A line of output from a compiler or other external
     * tool that might generate errors.
     * @return If the line is recognized as the start of an error message,
     * then return a class containing the path to the file on which the
     * error occurred (or null if there is none), the line number (or the
     * string "1" if there is none), the character position (or the string
     * "0" if there is none), and the message (or an empty string if there
     * is none).
     */
    protected ErrorFileAndLine parseCommandOutput(String line) {
        return null;
    }

    /**
     * Parse the specified string for command errors that can be reported
     * using marks in the Eclipse IDE. In this class, we attempt to parse
     * the messages to look for file and line information, thereby generating
     * marks on the appropriate lines. This should not be called in standalone
     * mode.
     *
     * @param stderr The output on standard error of executing a command.
     */
    public void reportCommandErrors(String stderr) {
        // NOTE: If the VS Code branch passes code review, then this function,
        //  parseCommandOutput, and ErrorFileAndLine will be deleted soon after.
        // First, split the message into lines.
        String[] lines = stderr.split("\\r?\\n");
        StringBuilder message = new StringBuilder();
        Integer lineNumber = null;
        Path path = context.getFileConfig().srcFile;
        // In case errors occur within an imported file, record the original path.
        Path originalPath = path;

        int severity = IMarker.SEVERITY_ERROR;
        for (String line : lines) {
            ErrorFileAndLine parsed = parseCommandOutput(line);
            if (parsed != null) {
                // Found a new line number designator.
                // If there is a previously accumulated message, report it.
                if (message.length() > 0) {
                    if (severity == IMarker.SEVERITY_ERROR)
                        errorReporter.reportError(path, lineNumber, message.toString());
                    else
                        errorReporter.reportWarning(path, lineNumber, message.toString());

                    if (!Objects.equal(originalPath.toFile(), path.toFile())) {
                        // Report an error also in the top-level resource.
                        // FIXME: It should be possible to descend through the import
                        // statements to find which one matches and mark all the
                        // import statements down the chain. But what a pain!
                        if (severity == IMarker.SEVERITY_ERROR) {
                            errorReporter.reportError(originalPath, 1, "Error in imported file: " + path);
                        } else {
                            errorReporter.reportWarning(originalPath, 1, "Warning in imported file: " + path);
                        }
                     }
                }
                if (parsed.isError) {
                    severity = IMarker.SEVERITY_ERROR;
                } else {
                    severity = IMarker.SEVERITY_WARNING;
                }

                // Start accumulating a new message.
                message = new StringBuilder();
                // Append the message on the line number designator line.
                message.append(parsed.message);

                // Set the new line number.
                try {
                    lineNumber = Integer.decode(parsed.line);
                } catch (Exception ex) {
                    // Set the line number unknown.
                    lineNumber = null;
                }
                // FIXME: Ignoring the position within the line.
                // Determine the path within which the error occurred.
                path = Paths.get(parsed.filepath);
            } else {
                // No line designator.
                if (message.length() > 0) {
                    message.append("\n");
                } else {
                    if (!line.toLowerCase().contains("error:")) {
                        severity = IMarker.SEVERITY_WARNING;
                    }
                }
                message.append(line);
            }
        }
        if (message.length() > 0) {
            if (severity == IMarker.SEVERITY_ERROR) {
                errorReporter.reportError(path, lineNumber, message.toString());
            } else {
                errorReporter.reportWarning(path, lineNumber, message.toString());
            }

            if (originalPath.toFile() != path.toFile()) {
                // Report an error also in the top-level resource.
                // FIXME: It should be possible to descend through the import
                // statements to find which one matches and mark all the
                // import statements down the chain. But what a pain!
                if (severity == IMarker.SEVERITY_ERROR) {
                    errorReporter.reportError(originalPath, 1, "Error in imported file: " + path);
                } else {
                    errorReporter.reportWarning(originalPath, 1, "Warning in imported file: " + path);
                }
            }
        }
    }

    // //////////////////////////////////////////////////
    // // Private functions

    /**
     * Print to stdout information about what source file is being generated,
     * what mode the generator is in, and where the generated sources are to be put.
     */
    public void printInfo(LFGeneratorContext.Mode mode) {
        System.out.println("Generating code for: " + context.getFileConfig().resource.getURI().toString());
        System.out.println("******** mode: " + mode);
        System.out.println("******** generated sources: " + context.getFileConfig().getSrcGenPath());
    }

    /**
     * Get the buffer type used for network messages
     */
    public String getNetworkBufferType() { return ""; }

    /**
     * Return the Targets enum for the current target
     */
    public abstract Target getTarget();

    /**
     * Get textual representation of a time in the target language.
     *
     * @param t A time AST node
     * @return A time string in the target language
     */
    // FIXME: this should be placed in ExpressionGenerator
    public static String getTargetTime(Time t) {
        TimeValue value = new TimeValue(t.getInterval(), TimeUnit.fromName(t.getUnit()));
        return timeInTargetLanguage(value);
    }

    /**
     * Get textual representation of a value in the target language.
     *
     * If the value evaluates to 0, it is interpreted as a normal value.
     *
     * @param expr An AST node
     * @return A string in the target language
     */
    // FIXME: this should be placed in ExpressionGenerator
    public static String getTargetValue(Expression expr) {
        if (expr instanceof Time) {
            return getTargetTime((Time)expr);
        }
        return ASTUtils.toText(expr);
    }

    /**
     * Get textual representation of a value in the target language.
     *
     * If the value evaluates to 0, it is interpreted as a time.
     *
     * @param expr A time AST node
     * @return A time string in the target language
     */
    // FIXME: this should be placed in ExpressionGenerator
    public static String getTargetTime(Expression expr) {
        if (expr instanceof Time) {
            return getTargetTime((Time)expr);
        } else if (ASTUtils.isZero(expr)) {
            TimeValue value = TimeValue.ZERO;
            return timeInTargetLanguage(value);
        }
        return ASTUtils.toText(expr);
    }
}
