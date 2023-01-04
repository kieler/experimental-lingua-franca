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

import org.eclipse.emf.common.util.ECollections;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.lflang.lf.AttrParm;
import org.lflang.lf.BehaviorTree;
import org.lflang.lf.BehaviorTreeNode;
import org.lflang.lf.Code;
import org.lflang.lf.Connection;
import org.lflang.lf.Fallback;
import org.lflang.lf.Input;
import org.lflang.lf.Instantiation;
import org.lflang.lf.LfFactory;
import org.lflang.lf.Local;
import org.lflang.lf.Model;
import org.lflang.lf.Output;
import org.lflang.lf.Port;
import org.lflang.lf.Reaction;
import org.lflang.lf.Reactor;
import org.lflang.lf.Sequence;
import org.lflang.lf.Task;
import org.lflang.lf.Type;
import org.lflang.lf.VarRef;
import org.lflang.lf.Variable;

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
    
//    public static Reactor rootReactor = null;

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
//            rootReactor = null;
            // btree mit nur inputs wird fehler auswerfen: Das ist nicht der Fix TODO
            if (bt.getRootNode() != null) {
                transformed.put(bt, transformBTree(bt, newReactors));                
            }
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
//        rootBTReactor.add(reactor);
        newReactors.add(reactor);
        reactor.setName(bt.getName());
        addBTNodeAnnotation(reactor, NodeType.ROOT.toString());

//        setBTInterface(reactor);
        // Init set all inputs and outputs from bt LF structur
        for(Input input : bt.getInputs()) {
            var copyInput = EcoreUtil.copy(input);
            reactor.getInputs().add(copyInput);
        }
        for (Output output : bt.getOutputs()) {
            var copyOutput = EcoreUtil.copy(output);
            reactor.getOutputs().add(copyOutput);
        }
        
        // Transform BT root
        var nodeReactor = transformNode(bt.getRootNode(), newReactors, reactor);
        var instance = LFF.createInstantiation();
        instance.setReactorClass(nodeReactor); // WICHTIG? wofür?
        instance.setName("root");
        reactor.getInstantiations().add(instance);

        // forward in and outputs of mock reactor to BT root node
        connectInOutputs(reactor, nodeReactor, instance);

        var connStart = createConn(reactor, null, START, nodeReactor, instance, START);
        reactor.getConnections().add(connStart);
        var connSuccess = createConn(nodeReactor, instance, SUCCESS, reactor, null, SUCCESS);
        reactor.getConnections().add(connSuccess);
        var connFailure = createConn(nodeReactor, instance, FAILURE, reactor, null, FAILURE);
        reactor.getConnections().add(connFailure);
//        addBTInputConnections(reactor, newReactors);
        
        return reactor;
    }

    // TODO delete this
//    private void addBTInOutputs(Reactor rootReactor, Reactor reactor) {
//        for(Input input : rootReactor.getInputs()) {
//            var copyInput = EcoreUtil.copy(input);
//            reactor.getInputs().add(copyInput);
////            inputsBT.add(input);
//        }
//        for (Output output : rootReactor.getOutputs()) {
//            var copyOutput = EcoreUtil.copy(output);
//            reactor.getOutputs().add(copyOutput);
//        }
//    }
    
    private void connectInOutputs(Reactor reactor, Reactor childReactor,
            Instantiation instance) {
        
        var childInputNames = new ArrayList<String>();
        for (Input inputChild : childReactor.getInputs()) {
            childInputNames.add(inputChild.getName());
        }
        
        for (Input in : reactor.getInputs()) {
            if (!in.getName().equals(START) && childInputNames.contains(in.getName())) {  //TODO !mb start signal aus transformBTree rausnehmen und hier rein
                var conn = createConn(reactor, null, in.getName(), childReactor, instance, in.getName());
                reactor.getConnections().add(conn);                
            }
        }
        
        var childOutputNames = new ArrayList<String>();
        for (Output outputChild : childReactor.getOutputs()) {
            childOutputNames.add(outputChild.getName());
        }

        for (Output out : reactor.getOutputs()) {
            if (!out.getName().equals(SUCCESS) && !out.getName().equals(FAILURE) && childOutputNames.contains(out.getName())) {
                var conn = createConn(childReactor, instance, out.getName(), reactor, null, out.getName());
                reactor.getConnections().add(conn);
                
            }
        }

    }
    
    

    // 1. Möglichkeit: Liste von Instantierungen weitergeben (oder als field)
    //     -> Problem, was wenn mehrere Reaktoren einen anderen instantiieren
    // 2. Möglichkeit: methode, die BT durchgeht und alle nodes abcheckt, ob die input haben wollen
    //        -> ineffizient
    // 3. Möglichkeit: Liste von Inputs durch ganze Transformation ziehen (oder als field)
    //         -> wie dann an BTroot methode die initierungen geben
    // 4. Möglichkeit: Liste von Connections
    //          -> gib root Reactor weiter, dann braucht man auch keinen extra parameter (aber keine instantierungen)
    // 5. Möglichkeit: FIELDS: 
    //                      Es lohnt sich nicht, nur den Tasks Input zu geben, die danach fragen,
    //                      weil man müsste die Sequence ganz durchgehen und gegebenfalls
    //                      noch weiter tief gehen um zu wissen ob jetzige Sequence/Fallback
    //                      auch den Input braucht (ineffizient)
    //                      Man könnte sonst allen SeqFb den Input geben und dann prüfen ob ein child den
    //                      Input braucht und wenn net dann den Input entfernen
    //      var fwdInputs = new HashMap<Reactor, ArrayList<String>>();
    //      und in schon geschriebenen Code hier die Instantierungen durch root durchgehen machen
    //       ODER fwdInputs = new HashMap<HashMap<Reactor, Instant>, ArrayList<String>>();
//    private void addBTInputConnections(Reactor rootNode, List<Reactor> newReactors) {
//        for (Input input : rootNode.getInputs()) {
//            for (Reactor r : newReactors) {
//                if (!r.equals(rootNode)) {
////              PROBLEM: keine Instantierungen mehr vorhanden
//                }
//            }       
//        }
//        
//    }

    private Reactor transformNode(BehaviorTreeNode node, List<Reactor> newReactors, Reactor rootReactor) {
        if (node instanceof Sequence) {
            return transformSequence((Sequence) node, newReactors, rootReactor);
        } else if (node instanceof Task) {
            return transformTask((Task) node, newReactors, rootReactor);
        } else if (node instanceof Fallback) {
            return transformFallback((Fallback) node, newReactors, rootReactor);
        } // TODO parralell
        return null;
    }

    private Reactor transformSequence(Sequence seq, List<Reactor> newReactors, Reactor rootReactor) {
        var reactor = LFF.createReactor();
        newReactors.add(reactor);
        reactor.setName("NodeSequence" + nodeNameCounter++);
        addBTNodeAnnotation(reactor, NodeType.SEQUENCE.toString());

        setBTInterface(reactor);

//        addBTInOutputs(rootReactor, reactor);
        
        // reaction will output failure, if any child produces failure
        Reaction reactionFailure = LFF.createReaction();
        Code failureCode = LFF.createCode();
        failureCode.setBody("lf_set(failure, true);");
        reactionFailure.setCode(failureCode);

        var failureEffect = createRef(reactor, null, FAILURE);
        reactionFailure.getEffects().add(failureEffect);

        int i = 0;
//        Reactor lastReactor = reactor;
//        Instantiation lastInstantiation = null;
        var last = new ReactorAndInst(reactor, null);
        var localSenders = new HashMap<String,ReactorAndInst>();
        for (var node : seq.getNodes()) {
            var nodeReactor = transformNode(node, newReactors, rootReactor);
            
            
            // instantiate child
            var instance = LFF.createInstantiation();
            instance.setReactorClass(nodeReactor);
            instance.setName("nodeSequenceInst" + seq.getNodes().indexOf(node));
            reactor.getInstantiations().add(instance);
            
            // TODO connect locals
            // add all inputs of childs to own inputs TODO if they are not locals
            // man muss noch zwischen Effect und Source unterscheiden
            var reactorInputNames = new ArrayList<String>();
            for (Input reactorInput : reactor.getInputs()) {
                reactorInputNames.add(reactorInput.getName());
            }
            for (Input rootInput : nodeReactor.getInputs()) {
                boolean isLocal = rootInput.getLocal() != null;
                if (!reactorInputNames.contains(rootInput.getName()) 
                        && !isLocal) { //TODO ineffizient, mb: liste, die dann am ende added wird
                    var copyInput = EcoreUtil.copy(rootInput);
                    reactor.getInputs().add(copyInput);
                }
                // Connect the inputs
                if (!rootInput.getName().equals(START) && !isLocal) {
                    var inputConn = createConn(reactor, null, rootInput.getName(), nodeReactor, instance, rootInput.getName());
                    reactor.getConnections().add(inputConn);
                }
                
            }
            
            // add all inputs of childs to own inputs // make this a method (mit option zwischen input output) TODO
            var reactorOutputNames = new ArrayList<String>();
            for (Output reactorOutput : reactor.getOutputs()) {
                reactorOutputNames.add(reactorOutput.getName());
            }
            for (Output rootOutput : nodeReactor.getOutputs()) {
                
                boolean isLocal = rootOutput.getLocal() != null;
                // Vorteil: man muss nicht mehr durch alle Attributes gehen.
//                for (var attr : rootOutput.getAttributes()) {
//                    if (attr.getAttrName().equals("isLocal")) {
//                        for (var attrparms : attr.getAttrParms()) {
//                            if (attrparms.getName().equals("local")) {
//                                isLocal = attrparms.getValue().getBool().equals("true");
//                                
//                            }
//                        }
//                    }
//                }
                if (!reactorOutputNames.contains(rootOutput.getName())
                        && !isLocal) { //TODO ineffizient, mb: liste, die dann am ende added wird
                    var copyOutput = EcoreUtil.copy(rootOutput);
                    reactor.getOutputs().add(copyOutput);
                }
                // Connect the Output
                if (!rootOutput.getName().equals(SUCCESS) && !rootOutput.getName().equals(FAILURE) && !isLocal) {
                    var outputConn = createConn(nodeReactor, instance, rootOutput.getName(), reactor, null, rootOutput.getName());
                    reactor.getConnections().add(outputConn);
                }
                
            }
            
            // add current childs failure output as failure output of
            // sequence reactor
            var failureTrigger = createRef(nodeReactor, instance, FAILURE);
            reactionFailure.getTriggers().add(failureTrigger);

            // Connections
            if (i == 0) {
                // sequence will first forward the start signal to the first
                // task
                var connStart = createConn(reactor, null, START, nodeReactor,
                        instance, START);
                reactor.getConnections().add(connStart);
            } else if (i < seq.getNodes().size()) {
                // if non-last task was successful start next task 
                var connForward = createConn(last.reactor, last.inst,
                        SUCCESS, nodeReactor, instance, START);
                reactor.getConnections().add(connForward);
            }
            // HOW TO DO CONNECTIONS? TODO: was tun wenn mehrere gleichen local haben?
            // TODO .getLocal() zu Bool machen.
//             Map<Reactor, Instantiation> NEIN LIEBER ALS CLASS
            // Map<localName, Reactor>
            for (Output output : nodeReactor.getOutputs()) {
                var localSender = new ReactorAndInst(nodeReactor, instance);
                if (output.getLocal() != null && output.getLocal().equals("true")) {
                    localSenders.put(output.getName(), localSender);
                }
            }
            for (Input input : nodeReactor.getInputs()) {
                if (input.getLocal() != null) {
                    var localSender = localSenders.get(input.getName());
                    var conn = createConn(localSender.reactor, localSender.inst, input.getName(), nodeReactor, instance, input.getName());
                    reactor.getConnections().add(conn);
                }
            }

            last.reactor = nodeReactor;
            last.inst = instance;
            i++;
        }
        // if last tasks output success, then sequence will output success
        var connSuccess = createConn(last.reactor, last.inst, SUCCESS,
                reactor, null, SUCCESS);
        reactor.getConnections().add(connSuccess);

        reactor.getReactions().add(reactionFailure);

        return reactor;
    }
    
    private Reactor transformFallback(Fallback fb, List<Reactor> newReactors, Reactor rootReactor) {
        var reactor = LFF.createReactor();
        newReactors.add(reactor);
        reactor.setName("Node" + nodeNameCounter++);
        addBTNodeAnnotation(reactor, NodeType.FALLBACK.toString());

        setBTInterface(reactor);
//        addBTInOutputs(rootReactor, reactor);

        // reaction will output failure, if any child produces failure
        Reaction reactionSuccess = LFF.createReaction();
        Code successCode = LFF.createCode();
        successCode.setBody("lf_set(success, true);");
        reactionSuccess.setCode(successCode);

        var successEffect = createRef(reactor, null, SUCCESS);
        reactionSuccess.getEffects().add(successEffect);

        int i = 0;
        Reactor lastReactor = reactor;
        Instantiation lastInstantiation = null;
        for (var node : fb.getNodes()) {
            var nodeReactor = transformNode(node, newReactors, rootReactor);
            
            
            
            // instantiate child
            var instance = LFF.createInstantiation();
            instance.setReactorClass(nodeReactor);
            instance.setName("node" + fb.getNodes().indexOf(node));
            reactor.getInstantiations().add(instance);
            
         // add all inputs of childs to own inputs
            var reactorInputNames = new ArrayList<String>();
            for (Input reactorInput : reactor.getInputs()) {
                reactorInputNames.add(reactorInput.getName());
            }
            for (Input rootInput : nodeReactor.getInputs()) {
                if (!reactorInputNames.contains(rootInput.getName())) { //TODO ineffizient, mb: liste, die dann am ende added wird
                    var copyInput = EcoreUtil.copy(rootInput);
                    reactor.getInputs().add(copyInput);
                }
                // Connect the inputs
                if (!rootInput.getName().equals(START)) {
                    var inputConn = createConn(reactor, null, rootInput.getName(), nodeReactor, instance, rootInput.getName());
                    reactor.getConnections().add(inputConn);
                }
                
            }
            
            // add all inputs of childs to own inputs // make this a method (mit option zwischen input output) TODO
            var reactorOutputNames = new ArrayList<String>();
            for (Output reactorOutput : reactor.getOutputs()) {
                reactorOutputNames.add(reactorOutput.getName());
            }
            for (Output rootOutput : nodeReactor.getOutputs()) {
                if (!reactorOutputNames.contains(rootOutput.getName())) { //TODO ineffizient, mb: liste, die dann am ende added wird
                    var copyOutput = EcoreUtil.copy(rootOutput);
                    reactor.getOutputs().add(copyOutput);
                }
                // Connect the Output
                if (!rootOutput.getName().equals(SUCCESS) && !rootOutput.getName().equals(FAILURE)) {
                    var outputConn = createConn(nodeReactor, instance, rootOutput.getName(), reactor, null, rootOutput.getName());
                    reactor.getConnections().add(outputConn);
                }
                
            }
            
            // add current child success output as success output of
            // sequence reactor
            var successTrigger = createRef(nodeReactor, instance, SUCCESS);
            reactionSuccess.getTriggers().add(successTrigger);

            // Connections
            if (i == 0) {
                // sequence will first forward the start signal to the first
                // task
                var connStart = createConn(reactor, null, START, nodeReactor,
                        instance, START);
                reactor.getConnections().add(connStart);
            } else if (i < fb.getNodes().size()) {
                // if non-last task was successful start next task 
                var connForward = createConn(lastReactor, lastInstantiation,
                        FAILURE, nodeReactor, instance, START);
                reactor.getConnections().add(connForward);
            }

            lastReactor = nodeReactor;
            lastInstantiation = instance;
            i++;
        }
        // if last tasks output failure, then fallback will output failure
        var connFailure = createConn(lastReactor, lastInstantiation, FAILURE,
                reactor, null, FAILURE);
        reactor.getConnections().add(connFailure);

        reactor.getReactions().add(reactionSuccess);

        return reactor;
    }

    private Reactor transformTask(Task task, List<Reactor> newReactors, Reactor rootReactor) {
        var reactor = LFF.createReactor();
        newReactors.add(reactor);
        // TODO richtige benennung von allen task, seq, fb
        String nameOfTask = task.getTaskName() == null ? 
                                "NodeTask" + (nodeNameCounter++) :
                                 task.getTaskName();
        reactor.setName(nameOfTask);
        
        String btNodeAnnot = task.isCondition() ?
                               NodeType.CONDITION.toString() :
                               NodeType.ACTION.toString();
        addBTNodeAnnotation(reactor, btNodeAnnot);
        
        setBTInterface(reactor);
//        addBTInOutputs(rootReactor, reactor);
        
        // set Inputs
        for (VarRef varref : task.getTaskSources()) {
            for (Input rootInput : rootReactor.getInputs()) {
                if (varref.getVariable().getName().equals(rootInput.getName())) {   //TODO ineffizient
                    // copy the input (wenn cpy net geht mach wie bei setBTInterface)
                    var copyInput = EcoreUtil.copy(rootInput);
                    reactor.getInputs().add(copyInput);
                }
            }
        }
        // set outputs
        for (VarRef varref : task.getTaskEffects()) {
            for (Output rootOutput : rootReactor.getOutputs()) {
                if (varref.getVariable().getName().equals(rootOutput.getName())) {   //TODO ineffizient
                    // copy the input (wenn cpy net geht mach wie bei setBTInterface)
                    var copyOutput = EcoreUtil.copy(rootOutput);
                    reactor.getOutputs().add(copyOutput);
                }
            }
        }
        
        // set local outputs
        if (!(task.eContainer() instanceof BehaviorTree)) { // nötig weil nur dann gehen wir hier durch
            var allLocals = new ArrayList<Local>();
            if (task.eContainer() instanceof Sequence) {
                allLocals.addAll(((Sequence) task.eContainer()).getLocals());
            } else {
                allLocals.addAll(((Fallback) task.eContainer()).getLocals());
            }
            
            for (VarRef varref : task.getTaskEffects()) {
                if (varref.getVariable() instanceof Local) {
                    for (Local taskLocal : allLocals) {
                        if (varref.getVariable().getName().equals(taskLocal.getName())) {
                            Output localOutput = LFF.createOutput();
                            localOutput.setName(varref.getVariable().getName());
                            var copyType = EcoreUtil.copy(taskLocal.getType());
                            localOutput.setType(copyType);
                            
                            // make this a method
//                            var localAttr = LFF.createAttribute();
//                            localAttr.setAttrName("isLocal");
//                            var localAttrParam = LFF.createAttrParm();
//                            localAttrParam.setName("local");
//                            var localAttrParamVal = LFF.createAttrParmValue();
//                            localAttrParamVal.setBool("true");
//                            localAttrParam.setValue(localAttrParamVal);
//                            localAttr.getAttrParms().add(localAttrParam);
//                            localOutput.getAttributes().add(localAttr);
                            localOutput.setLocal("true");
                            
                            reactor.getOutputs().add(localOutput);
                            
                        }
                    }
                    
                }
            }
        }
        
        // set local inputs
        if (!(task.eContainer() instanceof BehaviorTree)) {
            var allLocals = new ArrayList<Local>();
            if (task.eContainer() instanceof Sequence) {
                allLocals.addAll(((Sequence) task.eContainer()).getLocals());
            } else {
                allLocals.addAll(((Fallback) task.eContainer()).getLocals());
            }
            
            for (VarRef varref : task.getTaskSources()) {
                if (varref.getVariable() instanceof Local) {
                    for (Local taskLocal : allLocals) {
                        if (varref.getVariable().getName().equals(taskLocal.getName())) {
                            Input localInput = LFF.createInput();
                            localInput.setName(varref.getVariable().getName());
                            var copyType = EcoreUtil.copy(taskLocal.getType());
                            localInput.setType(copyType);
                            
//                            var localAttr = LFF.createAttribute();
//                            localAttr.setAttrName("local");
//                            var localAttrParam = LFF.createAttrParm();
//                            localAttrParam.setName("local");
//                            var localAttrParamVal = LFF.createAttrParmValue();
//                            localAttrParamVal.setBool("true");
//                            localAttrParam.setValue(localAttrParamVal);
//                            localAttr.getAttrParms().add(localAttrParam);
//                            localInput.getAttributes().add(localAttr);
                            localInput.setLocal("true");
                            
                            reactor.getInputs().add(localInput);
                        }
                    }
                }
            }
        }
        
        // WIRD NUR SCHWER SO GEHEN, WEIL AUF DIE WEISE NICHT SICHERGESTELLT WIRD,
        // DASS DER SEQ/FB VORHER AUCH DIE INPUTS BEKOMMT ODER NICHT! 
        //    -> STIMMT, deshalb neue vorangehensweise
//        for (VarRef varref : task.getTaskSources()) {
//            // put it into input
//            Variable variable = EcoreUtil.copy(varref.getVariable());
//            reactor.getInputs().add((Input) variable);
//            
//        }
        
        var reaction = LFF.createReaction();
        if (task.getCode() != null) {
//            reaction.setCode(task.getCode()); makes code go null
            var copyCode = EcoreUtil.copy(task.getCode());
            reaction.setCode(copyCode);
             
            var startTrigger = createRef(reactor, null, START);
            reaction.getTriggers().add(startTrigger);

            var successEffect = createRef(reactor, null, SUCCESS);
            reaction.getEffects().add(successEffect);

            var failureEffect = createRef(reactor, null, FAILURE);
            reaction.getEffects().add(failureEffect);

            for (VarRef varref : task.getTaskSources()) {
                var ref = createRef(reactor, null, varref.getVariable().getName());
                reaction.getSources().add(ref);
            }
            
            for (VarRef varref : task.getTaskEffects()) {
                var ref = createRef(reactor, null, varref.getVariable().getName());
                reaction.getEffects().add(ref);
            }
            
            reactor.getReactions().add(reaction);
        }

        return reactor;
    }

    private Connection createConn(Reactor leftR, Instantiation leftI,
            String leftPortName, Reactor rightR, Instantiation rightI,
            String rightPortName) {
        
        var connection = LFF.createConnection();
        var leftVarRef = createRef(leftR, leftI, leftPortName);
        var rightVarRef = createRef(rightR, rightI, rightPortName);

        connection.getLeftPorts().add(leftVarRef);

        connection.getRightPorts().add(rightVarRef);

        return connection;
    }

    private void setBTInterface(Reactor reactor) {
        // add start input
        Input startInput = LFF.createInput();
        startInput.setName("start");
        Type startType = LFF.createType();
        startType.setId("bool");
        startInput.setType(startType);
        reactor.getInputs().add(startInput);

        // add success output
        Output successOutput = LFF.createOutput();
        successOutput.setName("success");
        Type successType = LFF.createType();
        successType.setId("bool");
        successOutput.setType(successType);
        reactor.getOutputs().add(successOutput);

        // add failure output
        Output failureOutput = LFF.createOutput();
        failureOutput.setName("failure");
        Type failureType = LFF.createType();
        failureType.setId("bool");
        failureOutput.setType(failureType);
        reactor.getOutputs().add(failureOutput);
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
    
//    private boolean inputExistent(Reactor r, String portName) {
//        for (Input input : r.getInputs()) {
//            if(input.getName().equals(portName)) {
//                return true;
//            }
//        }
//        return false;
//    }

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
    class ReactorAndInst {
        Reactor reactor = null;
        Instantiation inst = null;
        
        private ReactorAndInst(Reactor reactor, Instantiation instantiation) {
            this.reactor = reactor;
            this.inst = instantiation;
        }
    }
}
