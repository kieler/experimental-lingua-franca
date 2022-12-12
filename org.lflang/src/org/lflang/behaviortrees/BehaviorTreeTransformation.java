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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.lflang.lf.BehaviorTree;
import org.lflang.lf.BehaviorTreeNode;
import org.lflang.lf.Code;
import org.lflang.lf.Connection;
import org.lflang.lf.Input;
import org.lflang.lf.Instantiation;
import org.lflang.lf.LfFactory;
import org.lflang.lf.Model;
import org.lflang.lf.Output;
import org.lflang.lf.Port;
import org.lflang.lf.Reaction;
import org.lflang.lf.Reactor;
import org.lflang.lf.Sequence;
import org.lflang.lf.Task;
import org.lflang.lf.Type;
import org.lflang.lf.VarRef;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;

/**
 * BehaviorTree AST transformation.
 * 
 * @author{Alexander Schulz-Rosengarten <als@informatik.uni-kiel.de>}
 */
public class BehaviorTreeTransformation {

    // BT node types
    public enum NodeType {
        ROOT, ACTION, CONDITION, SEQUENCE, FALLBACK, PARALLEL
    }

    // Interface port names
    public static String START = "start";
    public static String SUCCESS = "success";
    public static String FAILURE = "failure";

    public static void transform(Model lfModel) {
        new BehaviorTreeTransformation().transformAll(lfModel);
    }

    public static Reactor transformVirtual(BehaviorTree bt) {
        return new BehaviorTreeTransformation().transformBTree(bt,
                new ArrayList<Reactor>());
    }

    public static void addImplictInterface(BehaviorTree bt) {
        var type = LFF.createType();
        type.setId("bool");

        if (bt.getInputs().isEmpty() || bt.getInputs().stream()
                .noneMatch(it -> START.equals(it.getName()))) {
            var start = LFF.createInput();
            start.setName(START);
            start.setType(EcoreUtil.copy(type));
            bt.getInputs().add(start);
        }
        if (bt.getOutputs().isEmpty() || bt.getOutputs().stream()
                .noneMatch(it -> SUCCESS.equals(it.getName()))) {
            var succ = LFF.createOutput();
            succ.setName(SUCCESS);
            succ.setType(EcoreUtil.copy(type));
            bt.getOutputs().add(succ);
        }
        if (bt.getOutputs().isEmpty() || bt.getOutputs().stream()
                .noneMatch(it -> FAILURE.equals(it.getName()))) {
            var fail = LFF.createOutput();
            fail.setName(FAILURE);
            fail.setType(EcoreUtil.copy(type));
            bt.getOutputs().add(fail);
        }
    }

    static LfFactory LFF = LfFactory.eINSTANCE;

    private BehaviorTreeTransformation() {
    }

    private int nodeNameCounter = 0;

    private void transformAll(Model lfModel) {
        var newReactors = new ArrayList<Reactor>();
        var transformed = new HashMap<BehaviorTree, Reactor>();
        // Transform
        for (var bt : lfModel.getBtrees()) {
            transformed.put(bt, transformBTree(bt, newReactors));
        }
        // Fix references
        var instantiations = Lists.newArrayList(
                Iterators.filter(lfModel.eAllContents(), Instantiation.class));
        for (var i : instantiations) {
            if (transformed.containsKey(i.getReactorClass())) {
                // Replace BT by Reactor
                i.setReactorClass(transformed.get(i.getReactorClass()));
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
            }
        }
        // Remove BTrees
        lfModel.getBtrees().clear();
        // Add new reactors to model
        lfModel.getReactors().addAll(newReactors);

    }

    private Reactor transformBTree(BehaviorTree bt, List<Reactor> newReactors) {

        var reactor = LFF.createReactor();
        newReactors.add(reactor);
        reactor.setName(bt.getName());
        addBTNodeAnnotation(reactor, NodeType.ROOT.toString());

        setBTNodeStartInput(reactor);
        setBTNodeSuccessOutput(reactor);
        setBTNodeFailureOutput(reactor);

        var nodeReactor = transformNode(bt.getRootNode(), newReactors);
        var instance = LFF.createInstantiation();
        instance.setReactorClass(nodeReactor);
        instance.setName("root");
        reactor.getInstantiations().add(instance);

        var connStart = LFF.createConnection();

        var conFrom = createRef(reactor, null, START);
        connStart.getLeftPorts().add(conFrom);

        var conTo = createRef(nodeReactor, instance, START);
        connStart.getRightPorts().add(conTo);

        reactor.getConnections().add(connStart);

        var connSuccess = LFF.createConnection();

        var conFrom2 = createRef(nodeReactor, instance,
                SUCCESS);
        connSuccess.getLeftPorts().add(conFrom2);

        var conTo2 = createRef(reactor, null, SUCCESS);
        connSuccess.getRightPorts().add(conTo2);

        reactor.getConnections().add(connSuccess);

        var connFailure = LFF.createConnection();

        var conFrom3 = createRef(nodeReactor, instance,
                FAILURE);
        connFailure.getLeftPorts().add(conFrom3);

        var conTo3 = createRef(reactor, null, FAILURE);
        connFailure.getRightPorts().add(conTo3);

        reactor.getConnections().add(connFailure);

        return reactor;
    }

    private Reactor transformNode(BehaviorTreeNode node,
            List<Reactor> newReactors) {
        if (node instanceof Sequence) {
            return transformSequence((Sequence) node, newReactors);
        } else if (node instanceof Task) {
            return transformTask((Task) node, newReactors);
        }
        return null;
    }

    private Reactor transformSequence(Sequence seq, List<Reactor> newReactors) {
        var reactor = LFF.createReactor();
        newReactors.add(reactor);
        reactor.setName("Node" + nodeNameCounter++);
        addBTNodeAnnotation(reactor, NodeType.SEQUENCE.toString());

        setBTNodeStartInput(reactor);
        setBTNodeSuccessOutput(reactor);
        setBTNodeFailureOutput(reactor);

        // reaction will output failure, if any child produces failure
        Reaction reactionFailure = LFF.createReaction();
        Code failureCode = LFF.createCode();
        failureCode.setBody("lf_set(failure, true);");
        reactionFailure.setCode(failureCode);

        var failureEffect = createRef(reactor, null, FAILURE);
        reactionFailure.getEffects().add(failureEffect);

        int i = 0;
        Reactor lastReactor = reactor;
        Instantiation lastInstantiation = null;
        for (var node : seq.getNodes()) {
            var nodeReactor = transformNode(node, newReactors);
            // Instantiations
            var instance = LFF.createInstantiation();
            instance.setReactorClass(nodeReactor);
            instance.setName("node" + seq.getNodes().indexOf(node));
            reactor.getInstantiations().add(instance);
            // Failure
            var failureTrigger = createRef(nodeReactor, instance, FAILURE);
            reactionFailure.getTriggers().add(failureTrigger);

            // Connections
            if (i == 0) {
                // sequence will first forward the start signal to the first task
                var initConnection = LFF.createConnection();

                var initConnectionFrom = createRef(reactor, null, START);
                initConnection.getLeftPorts().add(initConnectionFrom);

                var initConnectionTo = createRef(nodeReactor, instance, START);
                initConnection.getRightPorts().add(initConnectionTo);

                reactor.getConnections().add(initConnection);
            } else if (i < seq.getNodes().size()) {
                // connect the tasks
                var connection = LFF.createConnection();

                var connFromVarRef = createRef(lastReactor, lastInstantiation,
                        SUCCESS);
                connection.getLeftPorts().add(connFromVarRef);

                var connToVarRef = createRef(nodeReactor, instance, START);
                connection.getRightPorts().add(connToVarRef);

                reactor.getConnections().add(connection);
            }

            lastReactor = nodeReactor;
            lastInstantiation = instance;
            i++;
        }
        // if last tasks output success, then sequence will output success
        var successConnection = LFF.createConnection();

        var successFromVarRef = createRef(lastReactor, lastInstantiation,
                SUCCESS);
        successConnection.getLeftPorts().add(successFromVarRef);

        var successToVarRef = createRef(reactor, null, SUCCESS);
        successConnection.getRightPorts().add(successToVarRef);

        reactor.getConnections().add(successConnection);

        reactor.getReactions().add(reactionFailure);

        return reactor;
    }

    private Reactor transformTask(Task task, List<Reactor> newReactors) {
        var reactor = LFF.createReactor();
        newReactors.add(reactor);
        reactor.setName("Node" + nodeNameCounter++);

        Input startInput = setBTNodeStartInput(reactor);
        Output successOutput = setBTNodeSuccessOutput(reactor);
        Output failureOutput = setBTNodeFailureOutput(reactor);

        addBTNodeAnnotation(reactor, NodeType.ACTION.toString());
        if (task.getReaction() != null) {
            var copyReaction = EcoreUtil.copy(task.getReaction());
            copyReaction.getTriggers().clear();

            var startTrigger = LFF.createVarRef();
            startTrigger.setVariable(startInput);
            copyReaction.getTriggers().add(startTrigger);

            var successEffect = LFF.createVarRef();
            successEffect.setVariable(successOutput);
            copyReaction.getEffects().add(successEffect);

            var failureEffect = LFF.createVarRef();
            failureEffect.setVariable(failureOutput);
            copyReaction.getEffects().add(failureEffect);

            reactor.getReactions().add(copyReaction);
        }

        return reactor;
    }

    private Input setBTNodeStartInput(Reactor reactor) {
        Input startInput = LFF.createInput();
        startInput.setName("start");
        Type startType = LFF.createType();
        startType.setId("bool");
        startInput.setType(startType);
        reactor.getInputs().add(startInput);
        return startInput;
    }

    private Output setBTNodeSuccessOutput(Reactor reactor) {
        Output successOutput = LFF.createOutput();
        successOutput.setName("success");
        Type successType = LFF.createType();
        successType.setId("bool");
        successOutput.setType(successType);
        reactor.getOutputs().add(successOutput);
        return successOutput;
    }

    private Output setBTNodeFailureOutput(Reactor reactor) {
        Output failureOutput = LFF.createOutput();
        failureOutput.setName("failure");
        Type failureType = LFF.createType();
        failureType.setId("bool");
        failureOutput.setType(failureType);
        reactor.getOutputs().add(failureOutput);

        return failureOutput;
    }

    private void addBTNodeAnnotation(Reactor reactor, String type) {
        var attr = LFF.createAttribute();
        attr.setAttrName("btnode");
        var param = LFF.createAttrParm();
        attr.getAttrParms().add(param);
        var value = LFF.createAttrParmValue();
        value.setStr(type);
        param.setValue(value);
        reactor.getAttributes().add(attr);
    }

    private VarRef createRef(Reactor r, Instantiation i, String portName) {
        var ref = LFF.createVarRef();
        var port = getPort(r, portName);

        if (port != null) {
            ref.setVariable(port);
            if (i != null) {
                ref.setContainer(i);
            }
            return ref;
        } else {
            return null;
        }
    }

    private Port getPort(Reactor r, String portName) {
        var opt = Stream.concat(r.getInputs().stream(), r.getOutputs().stream())
                .filter(p -> p.getName().equals(portName)).findFirst();
        if (opt.isPresent()) {
            return opt.get();
        } else {
            return null;
        }
    }
}
