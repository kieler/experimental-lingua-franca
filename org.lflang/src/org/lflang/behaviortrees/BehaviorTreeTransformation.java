/*************
 * Copyright (c) 2022, Kiel University.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 ***************/
package org.lflang.behaviortrees;

import static org.lflang.behaviortrees.BehaviorTrees.FAILURE;
import static org.lflang.behaviortrees.BehaviorTrees.RUNNING;
import static org.lflang.behaviortrees.BehaviorTrees.START;
import static org.lflang.behaviortrees.BehaviorTrees.SUCCESS;
import static org.lflang.behaviortrees.BehaviorTrees.TYPE_ANNOTATION_NAME;
import static org.lflang.behaviortrees.TransformationUtil.connect;
import static org.lflang.behaviortrees.TransformationUtil.createRef;
import static org.lflang.behaviortrees.TransformationUtil.getTypeOfNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.lflang.ASTUtils;
import org.lflang.Target;
import org.lflang.behaviortrees.BehaviorTrees.NodeType;
import org.lflang.lf.BehaviorTree;
import org.lflang.lf.BehaviorTreeCompoundNode;
import org.lflang.lf.BehaviorTreeNode;
import org.lflang.lf.Fallback;
import org.lflang.lf.Input;
import org.lflang.lf.Instantiation;
import org.lflang.lf.LfFactory;
import org.lflang.lf.Local;
import org.lflang.lf.Model;
import org.lflang.lf.Output;
import org.lflang.lf.Parallel;
import org.lflang.lf.Parameter;
import org.lflang.lf.Port;
import org.lflang.lf.Reactor;
import org.lflang.lf.Sequence;
import org.lflang.lf.SubTree;
import org.lflang.lf.Task;
import org.lflang.lf.VarRef;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;

/**
 * BehaviorTree AST transformation.
 * 
 * @author Alexander Schulz-Rosengarten
 */
public class BehaviorTreeTransformation {

    public static void transform(Model lfModel, Target target) {
        new BehaviorTreeTransformation(target).transformAll(lfModel);
    }

    public static Reactor transformVirtual(BehaviorTree bt) {
        return new BehaviorTreeTransformation(null).transformBTree(bt);
    }

    public static void addImplictInterface(BehaviorTree bt) {
        if (bt.getInputs().isEmpty() || bt.getInputs().stream()
                .noneMatch(it -> START.equals(it.getName()))) {
            var start = LFF.createInput();
            start.setName(START);
            start.setPure(true);
            bt.getInputs().add(start);
        }
        if (bt.getOutputs().isEmpty() || bt.getOutputs().stream()
                .noneMatch(it -> SUCCESS.equals(it.getName()))) {
            var succ = LFF.createOutput();
            succ.setName(SUCCESS);
            succ.setPure(true);
            bt.getOutputs().add(succ);
        }
        if (bt.getOutputs().isEmpty() || bt.getOutputs().stream()
                .noneMatch(it -> FAILURE.equals(it.getName()))) {
            var fail = LFF.createOutput();
            fail.setName(FAILURE);
            fail.setPure(true);
            bt.getOutputs().add(fail);
        }
        if (INFER_RUNNING) {
            if (bt.getOutputs().isEmpty() || bt.getOutputs().stream()
                    .noneMatch(it -> RUNNING.equals(it.getName()))) {
                var run = LFF.createOutput();
                run.setName(RUNNING);
                run.setPure(true);
                bt.getOutputs().add(run);
            }
        }
    }

    static boolean INFER_RUNNING = false;
    static final LfFactory LFF = LfFactory.eINSTANCE;

    /* only static public interface */
    private BehaviorTreeTransformation(Target target) {
        if (target != null) {
            switch (target) {
                case C:
                    codeGenerator = new CCodeGenerator();
                    break;
                case Python:
                    codeGenerator = new PythonCodeGenerator();
                    break;
                default:
                    throw new UnsupportedOperationException("Behavior tree do not yet support target language " + target.name());
            }
        } else {
            codeGenerator = new EmptyCodeGenerator();
        }
        connector = new DataConnector(codeGenerator);
    }

    private final CodeGenerator codeGenerator;
    private final DataConnector connector;
    private int nodeNameCounter = 0;
    private HashMap<BehaviorTree, Reactor> bTreeCache = new HashMap<>();
    private List<Reactor> newReactors = new ArrayList<>();
    private List<Parameter> currentParameters = new ArrayList<>();

    private void transformAll(Model lfModel) {
        var transformed = new HashMap<BehaviorTree, Reactor>();
        
        // Transform all trees
        for (var bt : lfModel.getBtrees()) {
            transformed.put(bt, transformBTree(bt));
        }
        
        // Fix references (map BT ports to Reactor ports)
        var instantiations = Lists.newArrayList(
                Iterators.filter(lfModel.eAllContents(), Instantiation.class));
        for (var i : instantiations) {
            if (transformed.containsKey(i.getReactorClass())) {
                var reactor = transformed.get(i.getReactorClass());
                // Replace BT by Reactor
                i.setReactorClass(reactor);
                // Change VarRefs to Port in reactor instead of BT
                var container = (Reactor) i.eContainer();
                var varRefs = Lists.newArrayList(Iterators
                        .filter(container.eAllContents(), VarRef.class));
                for (var v : varRefs) {
                    if (v.getContainer() == i) {
                        v.setVariable(createRef((Reactor) i.getReactorClass(),
                                i, v.getVariable().getName()).getVariable());
                    }
                }
                // Change parameter assignments to reactor
                var newParams = new HashMap<String, Parameter>();
                for (var p : reactor.getParameters()) {
                    newParams.put(p.getName(), p);
                }
                for (var asm : i.getParameters()) {
                    asm.setLhs(newParams.get(asm.getLhs().getName()));
                }
            }
        }
        
        // Remove BTrees
        lfModel.getBtrees().clear();
        // Add new reactors to model
        lfModel.getReactors().addAll(newReactors);
        // Add Pre reactor if necessary
        if (connector.usesPreReactor()) {
            lfModel.getReactors().add(connector.getPreReactorDefintion());
        }
    }
    
    private Reactor transformBTree(BehaviorTree bt) {
        if (bTreeCache.containsKey(bt)) {
            return bTreeCache.get(bt);
        }
        
        addImplictInterface(bt);
        
        var reactor = LFF.createReactor();
        bTreeCache.put(bt, reactor);
        newReactors.add(reactor);

        // Name and rendering annotation
        reactor.setName(bt.getName());
        addBTNodeAnnotation(reactor, NodeType.ROOT.toString());
        
        // Copy all parameters from BT. 
        for (var param : bt.getParameters()) {
            reactor.getParameters().add(EcoreUtil.copy(param));
        }
        currentParameters = reactor.getParameters();

        // Copy all inputs and outputs from BT. 
        for (var input : bt.getInputs()) {
            reactor.getInputs().add(EcoreUtil.copy(input));
        }
        for (var output : bt.getOutputs()) {
            reactor.getOutputs().add(EcoreUtil.copy(output));
        }
        
        // Transform BT root node
        if (bt.getRootNode() != null) {
            connector.analyze(bt);
            
            // Start recursive transformation
            var child = transformNode(bt.getRootNode(), "root");
            var childReactor = child.reactor;
            var childInstance = child.instance;
            reactor.getInstantiations().add(childInstance);
            
            // connect controlflow
            reactor.getConnections().addAll(List.of(
                    connect(reactor, null, START, childReactor, childInstance, START),
                    connect(childReactor, childInstance, SUCCESS, reactor, null, SUCCESS),
                    connect(childReactor, childInstance, FAILURE, reactor, null, FAILURE)
            ));
    
            // Forward in and outputs port to root node
            connector.connectRootIO(bt, reactor, child);
            
            // Forward parameters
            addParameterForwarding(reactor, childInstance);
        }
        
        if (INFER_RUNNING) {
            var reaction = LFF.createReaction();
            
            // interface
            reaction.getTriggers().add(createRef(reactor, null, SUCCESS));
            reaction.getTriggers().add(createRef(reactor, null, FAILURE));
            reaction.getEffects().add(createRef(reactor, null, RUNNING));
            
            var code = LFF.createCode();
            code.setBody(codeGenerator.getRunningInference());
            reaction.setCode(code);
        }
        
        return reactor;
    }
    
    private TransformedNode transformNode(BehaviorTreeNode node, String instanceName) {
        if (node instanceof SubTree) {
            return transformSubTree((SubTree) node);
        } else {
            // Create reactor
            var reactor = LFF.createReactor();
            newReactors.add(reactor);
    
            // Name and rendering annotation
            var type = getTypeOfNode(node);
            setLabelAndName(reactor, node, type);
            addBTNodeAnnotation(reactor, type.toString());
            
            // Copy parameters
            currentParameters.stream().map(EcoreUtil::copy).forEachOrdered(reactor.getParameters()::add);
            
            // Create instance
            var instance = LFF.createInstantiation();
            instance.setReactorClass(reactor);
            instance.setName(instanceName);
    
            // Add CF Interface
            addConrolflowInterface(reactor);
            
            if (node instanceof BehaviorTreeCompoundNode cnode) {
                var result = new TransformedNode(node, reactor, instance);
                
                // Transform children
                var children = new ArrayList<TransformedNode>();
                for (int i = 0; i < cnode.getNodes().size(); i++) {
                    var transformedChild = transformNode(cnode.getNodes().get(i), "child" + i);
                    reactor.getInstantiations().add(transformedChild.instance);
                    children.add(transformedChild);
                    
                    // Forward parameters
                    addParameterForwarding(reactor, transformedChild.instance);
                }
                
                if (!children.isEmpty()) {
                    conncetControlflow(cnode, reactor, children);
                    
                    result.setChildren(children);
                    
                    // Forward in and outputs port to root node
                    connector.connectData(result, children);
                }
                
                return result;
            } else if (node instanceof Task) {
                return transformTask((Task) node, reactor, instance);
            } else {
                throw new IllegalArgumentException("Unsupported type of node");
            }
        }
    }

    private TransformedNode transformSubTree(SubTree subtree) {
        // Get reactor of sub tree
        var btree = subtree.getBehaviorTree();
        var reactor = transformBTree(btree);
        
        // Create instance
        var instance = LFF.createInstantiation();
        instance.setReactorClass(reactor);
        instance.setName(subtree.getName());
        
        // Add label as instance label attribute
        if (subtree.getLabel() != null && !subtree.getLabel().isEmpty()) {
            var attr = LFF.createAttribute();
            attr.setAttrName("label");
            var param = LFF.createAttrParm();
            attr.getAttrParms().add(param);
            param.setValue("\""+subtree.getLabel()+"\"");
            reactor.getAttributes().add(attr);
        }
        
        var result = new TransformedNode(subtree, reactor, instance);
        // TODO pass bindings to result and handle them in upper levels
        
        return result;
    }

    private TransformedNode transformTask(Task task, Reactor reactor, Instantiation instance) {
        // Copy state vars
        if (task.getStateVars() != null) {
            for (var sv : task.getStateVars()) {
                reactor.getStateVars().add(EcoreUtil.copy(sv));
            }
        }

        // Create reaction
        var reaction = LFF.createReaction();
        reactor.getReactions().add(reaction);
        // - interface
        reaction.getTriggers().add(createRef(reactor, null, START));
        reaction.getEffects().add(createRef(reactor, null, SUCCESS));
        reaction.getEffects().add(createRef(reactor, null, FAILURE));
        // - body
        if (task.getCode() != null) {
            reaction.setCode(EcoreUtil.copy(task.getCode()));
            // The model does not reflect formatting, hence perform pre-format new code blocks similar to compilation
            reaction.getCode().setBody(ASTUtils.toText(task.getCode()));
        }
        
        var result = new TransformedNode(task, reactor, instance);

        // Data interface
        for (VarRef source : task.getSources()) {
            var port = source.getVariable();
            Port portCopy;
            if (port instanceof Input input) {
                portCopy = EcoreUtil.copy(input);
                reactor.getInputs().add((Input) portCopy);
                result.inputs.put((Input) portCopy, input);
            } else if (port instanceof Local local) {
                portCopy = transformToInput(local);
                reactor.getInputs().add((Input) portCopy);
                result.localsIn.put((Input) portCopy, local);
            } else {
                throw new IllegalArgumentException("Tasks should only refer to ports of the BT or locals");
            }
            var refCopy = EcoreUtil.copy(source);
            refCopy.setVariable(portCopy);
            reaction.getSources().add(refCopy);
        }
        for (VarRef effect : task.getEffects()) {
            var port = effect.getVariable();
            Port portCopy;
            if (port instanceof Output output) {
                portCopy = EcoreUtil.copy(output);
                reactor.getOutputs().add((Output) portCopy);
                result.outputs.put((Output) portCopy, output);
            } else if (port instanceof Local local) {
                portCopy = transformToOutput(local);
                reactor.getOutputs().add((Output) portCopy);
                result.localsOut.put((Output) portCopy, local);
            } else {
                throw new IllegalArgumentException("Tasks should only refer to ports of the BT or locals");
            }
            var refCopy = EcoreUtil.copy(effect);
            refCopy.setVariable(portCopy);
            reaction.getEffects().add(refCopy);
        }

        return result;
    }
    
    // Helper
    // ---------------
    
    /**
     * Add the attribute that will enable the special BT diagrams.
     * 
     * @param reactor
     * @param type
     */
    private void addBTNodeAnnotation(Reactor reactor, String type) {
        var attr = LFF.createAttribute();
        attr.setAttrName(TYPE_ANNOTATION_NAME);
        var param = LFF.createAttrParm();
        attr.getAttrParms().add(param);
        param.setValue("\""+type+"\"");
        reactor.getAttributes().add(attr);
    }
    
    /**
     * Sets the name and label of a reactor.
     * 
     * @param reactor
     * @param node
     * @param type
     */
    private void setLabelAndName(Reactor reactor, BehaviorTreeNode node, NodeType type) {
        var name = node.getName() != null && !node.getName().isEmpty()
                ? node.getName()
                : type.getRactorName() + nodeNameCounter++;
        reactor.setName(name);
        
        // Add label as reactor label attribute
        if (node.getLabel() != null && !node.getLabel().isEmpty()) {
            var attr = LFF.createAttribute();
            attr.setAttrName("label");
            var param = LFF.createAttrParm();
            attr.getAttrParms().add(param);
            param.setValue("\""+node.getLabel()+"\"");
            reactor.getAttributes().add(attr);
        }
    }
    
    /**
     * Adds the implicit BT interface for a reactor.
     * 
     * @param reactor
     */
    public static void addConrolflowInterface(Reactor reactor) {
        var start = LFF.createInput();
        start.setName(START);
        start.setPure(true);
        reactor.getInputs().add(start);
        
        var succ = LFF.createOutput();
        succ.setName(SUCCESS);
        succ.setPure(true);
        reactor.getOutputs().add(succ);
        
        var fail = LFF.createOutput();
        fail.setName(FAILURE);
        fail.setPure(true);
        reactor.getOutputs().add(fail);
    }
    

    /**
     * Adds parameter assignments to the instantiation to forward parameters.
     * 
     * @param reactor parent reactor
     * @param instance
     */
    private void addParameterForwarding(Reactor reactor, Instantiation instance) {
        for (int i = 0; i < reactor.getParameters().size(); i++) { // Assumes same size of both parameter lists!
            var param = reactor.getParameters().get(i);
            
            var asm = LFF.createAssignment();
            asm.setLhs(((Reactor) instance.getReactorClass()).getParameters().get(i));
            
            var init = LFF.createInitializer();
            var paramRef = LFF.createParameterReference();
            paramRef.setParameter(param);
            init.getExprs().add(paramRef);
            asm.setRhs(init);
            
            instance.getParameters().add(asm);
        }
    }
    
    /**
     * Copies a local connection and turns it into an Input.
     * 
     * @param local
     * @return
     */
    private Input transformToInput(Local local) {
        var port = LFF.createInput();
        port.setName(local.getName());
        port.setPure(local.isPure());
        port.setType(EcoreUtil.copy(local.getType()));
        port.setWidthSpec(EcoreUtil.copy(local.getWidthSpec()));
        local.getAttributes().stream().map(it -> EcoreUtil.copy(it)).forEachOrdered(port.getAttributes()::add);
        return port;
    }
    
    /**
     * Copies a local connection and turns it into an Output.
     * 
     * @param local
     * @return
     */
    private Output transformToOutput(Local local) {
        var port = LFF.createOutput();
        port.setName(local.getName());
        port.setPure(local.isPure());
        port.setType(EcoreUtil.copy(local.getType()));
        port.setWidthSpec(EcoreUtil.copy(local.getWidthSpec()));
        local.getAttributes().stream().map(it -> EcoreUtil.copy(it)).forEachOrdered(port.getAttributes()::add);
        return port;
    }
    
    /**
     * Creates the execution logic connections for compound nodes.
     * 
     * @param cnode
     * @param reactor
     * @param children
     */
    private void conncetControlflow(BehaviorTreeCompoundNode cnode,
            Reactor reactor, ArrayList<TransformedNode> children) {
        if (cnode instanceof Parallel parallel) {
            // Create reaction to calculate termination result
            var reaction = LFF.createReaction();
            reactor.getReactions().add(reaction);
            
            // Standard effects
            reaction.getEffects().add(createRef(reactor, null, SUCCESS));
            reaction.getEffects().add(createRef(reactor, null, FAILURE));
            
            for (var child : children) {
                reactor.getConnections().add(connect(reactor, null, START, child.reactor, child.instance, START));
                reaction.getTriggers().add(createRef(child.reactor, child.instance, SUCCESS));
                reaction.getTriggers().add(createRef(child.reactor, child.instance, FAILURE));
            }
            
            var code = LFF.createCode();
            code.setBody(codeGenerator.getParallelCalculation(parallel, reactor, children));
            reaction.setCode(code);
            
        } else { // Fallback & Sequence
            var firstChild = children.get(0);
            var lastChild = children.get(children.size() - 1);
            // - first and last
            reactor.getConnections().addAll(List.of(
                    connect(reactor, null, START, firstChild.reactor, firstChild.instance, START),
                    connect(lastChild.reactor, lastChild.instance, SUCCESS, reactor, null, SUCCESS),
                    connect(lastChild.reactor, lastChild.instance, FAILURE, reactor, null, FAILURE)
            ));
            // - in between
            for (int i = 1; i < children.size(); i++) { // skips first!
                var thisChild = children.get(i);
                var prevChild = children.get(i - 1);
                if (cnode instanceof Sequence) {
                    reactor.getConnections().addAll(List.of(
                            connect(prevChild.reactor, prevChild.instance, SUCCESS, thisChild.reactor, thisChild.instance, START),
                            connect(prevChild.reactor, prevChild.instance, FAILURE, reactor, null, FAILURE)
                    ));
                } else if (cnode instanceof Fallback) {
                    reactor.getConnections().addAll(List.of(
                            connect(prevChild.reactor, prevChild.instance, FAILURE, thisChild.reactor, thisChild.instance, START),
                            connect(prevChild.reactor, prevChild.instance, SUCCESS, reactor, null, SUCCESS)
                    ));
                }
            }
        }
    }
}

class TransformedNode {
    /** The source node in the BT. */
    public final BehaviorTreeNode node;
    /** The created reactor. */
    public final Reactor reactor;
    /** The instantiation of the reactor in its parent.  */
    public final Instantiation instance;
    /** Map of input ports. Key: Port in this reactor, Value: Original port in the BT. */
    public final LinkedHashMap<Input, Input> inputs = new LinkedHashMap<>();
    /** Map of output ports. Key: Port in this reactor, Value: Original port in the BT. */
    public final LinkedHashMap<Output, Output> outputs = new LinkedHashMap<>();
    /** Map of read local ports. Key: Port in this reactor, Value: Original local in the BT. */
    public final LinkedHashMap<Input, Local> localsIn = new LinkedHashMap<>();
    /** Map of written local ports. Key: Port in this reactor, Value: Original local in the BT. */
    public final LinkedHashMap<Output, Local> localsOut = new LinkedHashMap<>();
    /** List of all transformed children of this node. */
    public final List<TransformedNode> children = new ArrayList<TransformedNode>();

    TransformedNode(
        BehaviorTreeNode node, 
        Reactor reactor,
        Instantiation instance) {
        this.node = node;
        this.reactor = reactor;
        this.instance = instance;
    }

    public void setChildren(ArrayList<TransformedNode> children) {
        this.children.clear();
        this.children.addAll(children);
    }

    boolean isSubTree() {
        return node instanceof SubTree;
    }
}
