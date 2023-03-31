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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.emf.ecore.util.EcoreUtil;
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
import org.lflang.lf.Parallel;
import org.lflang.lf.Port;
import org.lflang.lf.Reaction;
import org.lflang.lf.Reactor;
import org.lflang.lf.Sequence;
import org.lflang.lf.Task;
import org.lflang.lf.Type;
import org.lflang.lf.VarRef;

import com.fasterxml.jackson.core.io.InputDecorator;
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
        newReactors.add(reactor);
        reactor.setName(bt.getName());
        addBTNodeAnnotation(reactor, NodeType.ROOT.toString());

        // Init set all inputs and outputs from declared bt. 
        addImplictInterface(bt);
        copyInOutputs(reactor, bt.getOutputs(), bt.getInputs());
        
        
        var localOuts = new HashMap<Local, List<String>>();   // TODO change to List<Integer>
        var localIns = new HashMap<Local, List<String>>();
        computeLocalPaths(bt.getRootNode(), "0", localOuts, localIns);
        var nodesLocals = computeNodesLocals(bt.getRootNode(), localOuts, localIns, new HashMap<BehaviorTreeNode, NodesLocalOutInputs>());
        // Transform BT root
        var nodeReactor = transformNode(bt.getRootNode(), newReactors, nodesLocals);
        var instance = LFF.createInstantiation();
        instance.setReactorClass(nodeReactor);
        instance.setName("root");
        reactor.getInstantiations().add(instance);

        // forward in and outputs of mock reactor to BT root node
        connectInOutputs(reactor, nodeReactor, instance); // TODO problem: leere parallel kriegt kein bt implicit interface (ist im scope provider)
        
        // nutze Start input as trigger
        
//        var outputRunning = LFF.createOutput();
//        outputRunning.setName("running");
//        var type = LFF.createType();
//        type.setId("bool");
//        outputRunning.setType(type);
//        
//        reactor.getOutputs().add(outputRunning);
//        
//        var reaction = LFF.createReaction();
//        var code = LFF.createCode();
//        code.setBody("if (!success->is_present && !failure->is_present) {\n"
//                + "    SET(running, true);\n}");
//        reaction.setCode(code);
//        var startUpTrigger = LFF.createBuiltinTriggerRef();
////        var builtInTrigger = new BuiltinTrigger();
//        startUpTrigger.setType(null);
//        reaction.getTriggers().add(null);
//        reaction.getEffects().add(createRef(reactor, null, "running"));
//        reactor.getReactions().add(reaction);
        
        return reactor;
    }
    
    private void copyInOutputs (Reactor reactor, List<Output> outputs, List<Input> inputs) {
        if (inputs != null) {
            for (Input input : inputs) {
                var copyInput = EcoreUtil.copy(input);
                reactor.getInputs().add(copyInput);
            }            
        }
        
        if (outputs != null) {
            for (Output output : outputs) {
                var copyOutput = EcoreUtil.copy(output);
                reactor.getOutputs().add(copyOutput);
            }            
        }
    }
    
    private void connectInOutputs(Reactor reactor, Reactor childReactor,
            Instantiation instance) {
        var childInputNames = childReactor.getInputs().stream().map(x -> x.getName()).collect(Collectors.toList());
        
        for (Input in : reactor.getInputs()) {
            if (childInputNames.contains(in.getName())) {
                var conn = createConn(reactor, null, in.getName(), childReactor, instance, in.getName());
                reactor.getConnections().add(conn);                
            }
        }
        
        var childOutputNames = childReactor.getOutputs().stream().map(x -> x.getName()).collect(Collectors.toList());

        for (Output out : reactor.getOutputs()) {
            if (childOutputNames.contains(out.getName())) {
                var conn = createConn(childReactor, instance, out.getName(), reactor, null, out.getName());
                reactor.getConnections().add(conn);
                
            }
        }

    }
                    // TODO: change inputDep and outputDep NICHT MEHR 2 hashmaps sondern rechte seite einfach neue klasse
    private void computeLocalPaths(BehaviorTreeNode seqOrFb, String path, HashMap<Local, List<String>> outputDep, HashMap<Local, List<String>> inputDep) {
        if (seqOrFb instanceof Parallel) return; // EList getNodes ist sortiert ! wichtig für paths
        if (seqOrFb instanceof Task) return;
        
        Sequence seq = null;
        Fallback fb = null;
        Parallel par = null;
        if (seqOrFb instanceof Sequence) seq = (Sequence) seqOrFb;
        if (seqOrFb instanceof Fallback) fb = (Fallback) seqOrFb;
        if (seqOrFb instanceof Parallel) par = (Parallel) seqOrFb;
        
        int i = 0;
        if (seq != null) {
            for (var node : seq.getNodes()) {
                if (node instanceof Task) {
                    for (var srcRef : ((Task) node).getTaskSources()) {
                        if (srcRef.getVariable() instanceof Local) {
                            var localIn = (Local) srcRef.getVariable();
                            var pathList = inputDep.get(localIn);
                            if (pathList == null) {
                                pathList = new ArrayList<String>();
                            }
                            pathList.add(path + "-" + i);
                            inputDep.put(localIn, pathList);
                        }
                    }
                    for (var effRef : ((Task) node).getTaskEffects()) {
                        if (effRef.getVariable() instanceof Local) {
                            var localOut = (Local) effRef.getVariable();
                            var pathList = outputDep.get(localOut);
                            if (pathList == null) {
                                pathList = new ArrayList<String>();
                            }
                            pathList.add(path + "-" + i);
                            outputDep.put(localOut, pathList);
                        }
                    }
                } else if (node instanceof Sequence || node instanceof Fallback || node instanceof Parallel) {
                    String debugPath = path + "-" + i;
                    computeLocalPaths(node, debugPath, outputDep, inputDep);
                }
                i++;
            }
        } else if (fb != null) {
            for (var node : fb.getNodes()) {
                if (node instanceof Task) {
                    for (var srcRef : ((Task) node).getTaskSources()) {
                        if (srcRef.getVariable() instanceof Local) {
                            var localIn = (Local) srcRef.getVariable();
                            var pathList = inputDep.get(localIn);
                            if (pathList == null) {
                                pathList = new ArrayList<String>();
                            }
                            pathList.add(path + "-" + i);
                            inputDep.put(localIn, pathList);
                        }
                    }
                    for (var effRef : ((Task) node).getTaskEffects()) {
                        if (effRef.getVariable() instanceof Local) {
                            var localOut = (Local) effRef.getVariable();
                            var pathList = outputDep.get(localOut);
                            if (pathList == null) {
                                pathList = new ArrayList<String>();
                            }
                            pathList.add(path + "-" + i);
                            outputDep.put(localOut, pathList);
                        }
                    }
                } else if (node instanceof Sequence || node instanceof Fallback) {
                    String debugPath = path + "-" + i;
                    computeLocalPaths(node, debugPath, outputDep, inputDep);
                }
                i++;
            }
        } else if (par != null) {
            for (var node : par.getNodes()) {
                if (node instanceof Task) {
                    for (var srcRef : ((Task) node).getTaskSources()) {
                        if (srcRef.getVariable() instanceof Local) {
                            var localIn = (Local) srcRef.getVariable();
                            var pathList = inputDep.get(localIn);
                            if (pathList == null) {
                                pathList = new ArrayList<String>();
                            }
                            pathList.add(path + "-" + i);
                            inputDep.put(localIn, pathList);
                        }
                    }
                    for (var effRef : ((Task) node).getTaskEffects()) {
                        if (effRef.getVariable() instanceof Local) {
                            var localOut = (Local) effRef.getVariable();
                            var pathList = outputDep.get(localOut);
                            if (pathList == null) {
                                pathList = new ArrayList<String>();
                            }
                            pathList.add(path + "-" + i);
                            outputDep.put(localOut, pathList);
                        }
                    }
                } else if (node instanceof Sequence || node instanceof Fallback || node instanceof Parallel) {
                    String debugPath = path + "-" + i;
                    computeLocalPaths(node, debugPath, outputDep, inputDep);
                }
                i++;
            }
        }
        
    }

    private HashMap<BehaviorTreeNode, NodesLocalOutInputs> computeNodesLocals(BehaviorTreeNode root, HashMap<Local, List<String>> outDep, HashMap<Local, List<String>> inDep, HashMap<BehaviorTreeNode, NodesLocalOutInputs> result) {
        HashSet<Local> set = new HashSet<Local>(outDep.keySet());
        set.addAll(inDep.keySet());
        for (var local : set) {
            var outputs = outDep.get(local);
            var inputs = inDep.get(local);
            if (inputs != null && outputs != null) {
                var lastReceiver = inputs.get(inputs.size() - 1);
                var firstSender = outputs.get(0);
                
                iterativeOutputComputation(root, local, outDep.get(local) ,lastReceiver, result);
                iterativeInputComputation(root, local, inDep.get(local), firstSender, result);                
            }
        }
//        for (var output : outDep.entrySet()) {  // Wäre etwas übersichtlicher wenn ein local zurzeit durchgegangen wird
//            var local = output.getKey();
//            var inputs = inDep.get(local);
//            var lastReceiver = inputs.get(inputs.size() - 1);
//            recursiveOutputComputation(root, local, output.getValue() ,lastReceiver, result);
//        }
//        for (var input : inDep.entrySet()) {
//            var local = input.getKey();
//            var outputs = outDep.get(local);
//            var firstSender = outputs.get(0);
//            recursiveInputComputation(root, local, input.getValue(), firstSender, result);
//        }
        
        return result;
    }
    
    private void iterativeInputComputation(BehaviorTreeNode root, Local local, List<String> paths , String firstSender, HashMap<BehaviorTreeNode, NodesLocalOutInputs> result) {
        var firstSenderTemp = firstSender.split("-");
        var firstSenderArr = Stream.of(firstSenderTemp).mapToInt(Integer::parseInt).toArray();

        
        for (var path : paths) {
            Sequence seq = null;
            Fallback fb = null;
            Parallel par = null;
            if (root instanceof Sequence) seq = (Sequence) root;
            if (root instanceof Fallback) fb = (Fallback) root;
            if (root instanceof Parallel) par = (Parallel) root;
            var pathTemp = path.split("-");
            var pathArr = Stream.of(pathTemp).mapToInt(Integer::parseInt).toArray();
            if (firstPathSmallerThanSecond(firstSenderArr, pathArr)) { // Wenn der local output geschhrieben wird, obwohl danach keiner liest, dann gehen wir hier nicht rein
                var smallerLength = pathArr.length < firstSenderArr.length ? pathArr.length : firstSenderArr.length;
                BehaviorTreeNode nextChild = null;
                for (int i = 0; i < smallerLength; i++) {
                    
                    if (seq != null) {
                        nextChild = seq.getNodes().get(pathArr[i+1]);
                    } else if (fb != null) {
                        nextChild = fb.getNodes().get(pathArr[i+1]);
                    } else if (par != null) {
                        nextChild = par.getNodes().get(pathArr[i+1]);
                    }
                    
                    if (pathArr[i] != firstSenderArr[i]) {
                        // add local to seq and all child along path
                        int j = 0;
                        while (true) {
                            NodesLocalOutInputs seqLocalInAndOuts = null;
                            if (seq != null) {      // PUT THIS in a method
                                seqLocalInAndOuts = result.get(seq);
                            } else if (fb != null) {
                                seqLocalInAndOuts = result.get(fb);
                            } else if (par != null) {
                                seqLocalInAndOuts = result.get(par);
                            }
                            if (seqLocalInAndOuts == null) {
                                seqLocalInAndOuts = new NodesLocalOutInputs();
                            }
                            seqLocalInAndOuts.inputs.add(local);
                            if (seq != null) { 
                                result.put(seq, seqLocalInAndOuts);
                            } else if (fb != null) {
                                result.put(fb, seqLocalInAndOuts);
                            } else if (par != null) {
                                result.put(par, seqLocalInAndOuts);
                            }
                            if (nextChild instanceof Task) {
                                var taskLocalInAndOuts = result.get(nextChild);
                                if (taskLocalInAndOuts == null) {
                                    taskLocalInAndOuts = new NodesLocalOutInputs();
                                }
                                taskLocalInAndOuts.inputs.add(local);
                                result.put(nextChild, taskLocalInAndOuts);
                                break;
                            }
                            seq = null;
                            fb = null;
                            par = null;
                            if (nextChild instanceof Sequence) seq = (Sequence) nextChild;
                            if (nextChild instanceof Fallback) fb = (Fallback) nextChild;
                            if (nextChild instanceof Parallel) par = (Parallel) nextChild;
                            if (seq != null) {
                                nextChild = seq.getNodes().get(pathArr[i+1]);
                            } else if (fb != null) {
                                nextChild = fb.getNodes().get(pathArr[i+1]);
                            } else if (par != null) {
                                nextChild = par.getNodes().get(pathArr[i+1]);
                            }
                            nextChild = seq.getNodes().get(pathArr[i+j]);
                            j++;
                        }
                        break;
                    }
                    if (nextChild instanceof Task) {
                        var taskLocalInAndOuts = result.get(nextChild);
                        if (taskLocalInAndOuts == null) {
                            taskLocalInAndOuts = new NodesLocalOutInputs();
                        }
                        taskLocalInAndOuts.inputs.add(local);
                        result.put(nextChild, taskLocalInAndOuts);
                        break;
                    }
                    seq = null;
                    fb = null;
                    par = null;
                    if (nextChild instanceof Sequence) seq = (Sequence) nextChild;
                    if (nextChild instanceof Fallback) fb = (Fallback) nextChild;
                    if (nextChild instanceof Parallel) par = (Parallel) nextChild;
                    
                }
            }
        }
    }
    
    private void iterativeOutputComputation(BehaviorTreeNode root, Local local, List<String> paths , String lastReceiver, HashMap<BehaviorTreeNode, NodesLocalOutInputs> result) {
        var lastRcvTemp = lastReceiver.split("-");
        var lastRecvArr = Stream.of(lastRcvTemp).mapToInt(Integer::parseInt).toArray();
        
        for (var path : paths) {
            Sequence seq = null;
            Fallback fb = null;
            Parallel par = null;
            if (root instanceof Sequence) seq = (Sequence) root;
            if (root instanceof Fallback) fb = (Fallback) root;
            if (root instanceof Parallel) par = (Parallel) root;
            var pathTemp = path.split("-");
            var pathArr = Stream.of(pathTemp).mapToInt(Integer::parseInt).toArray();
            if (firstPathSmallerThanSecond(pathArr, lastRecvArr)) { // Wenn der local output geschhrieben wird, obwohl danach keiner liest, dann gehen wir hier nicht rein
                var smallerLength = pathArr.length < lastRecvArr.length ? pathArr.length : lastRecvArr.length;
                BehaviorTreeNode nextChild = null;
                for (int i = 0; i < smallerLength; i++) {
                    
                    if (seq != null) {
                        nextChild = seq.getNodes().get(pathArr[i+1]);
                    } else if (fb != null) {
                        nextChild = fb.getNodes().get(pathArr[i+1]);
                    } else if (par != null) {
                        nextChild = par.getNodes().get(pathArr[i+1]);
                    }
                    
                    if (pathArr[i] != lastRecvArr[i]) {
                        // add local to seq and all child along path
                        int j = 0;
                        while (true) {
                            NodesLocalOutInputs seqLocalInAndOuts = null;
                            if (seq != null) {      // PUT THIS in a method
                                seqLocalInAndOuts = result.get(seq);
                            } else if (fb != null) {
                                seqLocalInAndOuts = result.get(fb);
                            } else if (par != null) {
                                seqLocalInAndOuts = result.get(par);
                            }
                            if (seqLocalInAndOuts == null) {
                                seqLocalInAndOuts = new NodesLocalOutInputs();
                            }
                            seqLocalInAndOuts.outputs.add(local);
                            if (seq != null) { 
                                result.put(seq, seqLocalInAndOuts);
                            } else if (fb != null) {
                                result.put(fb, seqLocalInAndOuts);
                            } else if (par != null) {
                                result.put(par, seqLocalInAndOuts);
                            }
                            if (nextChild instanceof Task) {
                                var taskLocalInAndOuts = result.get(nextChild);
                                if (taskLocalInAndOuts == null) {
                                    taskLocalInAndOuts = new NodesLocalOutInputs();
                                }
                                taskLocalInAndOuts.outputs.add(local);
                                result.put(nextChild, taskLocalInAndOuts);
                                break;
                            }
                            seq = null;
                            fb = null;
                            par = null;
                            if (nextChild instanceof Sequence) seq = (Sequence) nextChild;
                            if (nextChild instanceof Fallback) fb = (Fallback) nextChild;
                            if (nextChild instanceof Parallel) par = (Parallel) nextChild;
                            if (seq != null) {
                                nextChild = seq.getNodes().get(pathArr[i+1]);
                            } else if (fb != null) {
                                nextChild = fb.getNodes().get(pathArr[i+1]);
                            } else if (par != null) {
                                nextChild = par.getNodes().get(pathArr[i+1]);
                            }
                            nextChild = seq.getNodes().get(pathArr[i+j]);
                            j++;
                        }
                        break;
                    }
                    if (nextChild instanceof Task) {
                        var taskLocalInAndOuts = result.get(nextChild);
                        if (taskLocalInAndOuts == null) {
                            taskLocalInAndOuts = new NodesLocalOutInputs();
                        }
                        taskLocalInAndOuts.outputs.add(local);
                        result.put(nextChild, taskLocalInAndOuts);
                        break;
                    }
                    seq = null;
                    fb = null;
                    par = null;
                    if (nextChild instanceof Sequence) seq = (Sequence) nextChild;
                    if (nextChild instanceof Fallback) fb = (Fallback) nextChild;
                    if (nextChild instanceof Parallel) par = (Parallel) nextChild;
                    
                }
            }
        }
    }
    
    private boolean firstPathSmallerThanSecond(int[] first, int[] second) {
        var smallerSize = first.length < second.length ? first.length : second.length;
        for (int i = 0; i < smallerSize; i++) {
            if (first[i] < second[i]) return true;
            if (first[i] > second[i]) return false;
        }
        return true;
    }
    
    private Reactor transformNode(BehaviorTreeNode node, List<Reactor> newReactors, HashMap<BehaviorTreeNode, NodesLocalOutInputs> nodeToLocalOutInputs) {
        if (node instanceof Sequence) {
            return transformSequence((Sequence) node, newReactors, nodeToLocalOutInputs);
        } else if (node instanceof Task) {
            return transformTask((Task) node, newReactors, nodeToLocalOutInputs);
        } else if (node instanceof Fallback) {
            return transformFallback((Fallback) node, newReactors, nodeToLocalOutInputs);
        } else if (node instanceof Parallel) {
            return transformParallel((Parallel) node, newReactors, nodeToLocalOutInputs);
        }
        return null;
    }

    private Reactor transformSequence(Sequence seq, List<Reactor> newReactors, HashMap<BehaviorTreeNode, NodesLocalOutInputs> nodeToLocalOutInputs) {
        // TODO vielleicht LÖSUNG FÜR BESSERE PERFORMANCE: LISTE MIT INPUTS UND OUTPUTS    var inputs = new ArrayList<Input>();
        var reactor = LFF.createReactor();
        newReactors.add(reactor);
        reactor.setName("Seq" + nodeNameCounter++);
        addBTNodeAnnotation(reactor, NodeType.SEQUENCE.toString());

        setBTInterface(reactor);
        
        
        

        var seqLocalInOutputs = nodeToLocalOutInputs.remove(seq);
        
        if (seqLocalInOutputs != null) {
            if (!seqLocalInOutputs.inputs.isEmpty()) {
                for (var localToInput : seqLocalInOutputs.inputs) {
                    var input = LFF.createInput();
                    input.setName(localToInput.getName());
                    var copyType = EcoreUtil.copy(localToInput.getType());
                    input.setType(copyType);
                    
                    input.setLocal("true");
                    
                    reactor.getInputs().add(input);
                }
            }
            
            if (!seqLocalInOutputs.outputs.isEmpty()) {
                for (var localToOutput : seqLocalInOutputs.outputs) {
                    var output = LFF.createOutput();
                    output.setName(localToOutput.getName());
                    var copyType = EcoreUtil.copy(localToOutput.getType());
                    output.setType(copyType);
                    
                    output.setLocal("true");
                    
                    reactor.getOutputs().add(output);
                }
            }
        }
        
     // reaction will output failure, if any child produces failure
        var reactionFailure = createMergedOutputReaction(reactor, FAILURE);
        
        int i = 0;
        var last = new ReactorAndInst(reactor, null); // kann wieder lastR und lastIn sein
        var localCompleted = new ArrayList<TriggerAndEffectRefs>();
        var localCommunication = new TreeMap<String,TriggerAndEffectRefs>(); // TreeMap um die Reihenfolge zu behalten // <LocalNameAndLock verworfen, weil dann net mehr suche geht (sonst zweite HashMap) TODO besprechen
        var sequentialOutputs = new HashMap<String, ArrayList<VarRef>>();
        for (var node : seq.getNodes()) {
            
            
            var nodeReactor = transformNode(node, newReactors, nodeToLocalOutInputs);  // hier ja den path weniger machen
            
            // instantiate child
            var instance = LFF.createInstantiation();
            instance.setReactorClass(nodeReactor);
            instance.setName(nodeReactor.getName() + "_i");
            reactor.getInstantiations().add(instance);
            
            // add all inputs of childs to own inputs TODO if they are not locals   // hier filter hinzufügen und man spart sich start schleifendurchgang
            var reactorInputNames = reactor.getInputs().stream().map(x -> x.getName()).collect(Collectors.toList());
            
            for (Input nodeInput : nodeReactor.getInputs()) {
                var inputName = nodeInput.getName();
                if (!inputName.equals(START)) {
                    
                    if (nodeInput.getLocal() == null) {
                        if (!reactorInputNames.contains(inputName)) {
                            var copyInput = EcoreUtil.copy(nodeInput);
                            reactor.getInputs().add(copyInput);
                        }
                        var inputConn = createConn(reactor, null, inputName, nodeReactor, instance, inputName);
                        reactor.getConnections().add(inputConn);
                    } else {
                        
                        var keyName = localCommunication.keySet().stream().filter(x -> x.contains(inputName)).findFirst().orElse(null);
                        TriggerAndEffectRefs triggsAndEffcs = null;
                        if (keyName != null) {
                            triggsAndEffcs = localCommunication.get(keyName);
                        }
                        
                        if (triggsAndEffcs == null) { // DH ES ist ein local und keine task innerhalb der sequence gerad hat auf den geschrieben -> ERGO EINFACH DIREKT VERBINDEN
                            if (reactorInputNames.contains(inputName)) { // DANN MÜSSEN WIR UNS VON DRAUSSEN HOLEN, ABER EIG SOLL DIESE CONN JA AUCH TROTZDEM GEMACHT WERDEN, WEIL WAS WENN DER TASK, DER IN DER SEQUENCE SCHREIBEN KANN ABER NICHT TUT, DANN HABEN WIR KEIN VAL! UPDATE: GEHT IWIE TROTZDEM CHECK MAL AB WIESO UPDATE: ES GEHT WEIL ZEILE 732 (triggsAndEffcs.triggerRefs.add(createRef(reactor, null, outputName));)
                                var inputConn = createConn(reactor, null, inputName, nodeReactor, instance, inputName);
                                reactor.getConnections().add(inputConn);
                            }
                            
                        } else {
                               String localNameWriteLock = keyName;
                               if (keyName.charAt(0) != '#') {
                                   localCommunication.remove(keyName);
                                   localNameWriteLock = "#" + keyName;
                               }
                               triggsAndEffcs.effectRefs.add(createRef(nodeReactor, instance, nodeInput.getName()));
                               localCommunication.put(localNameWriteLock, triggsAndEffcs);
                           }
                    }
                }
            }
            var reactorOutputNames = reactor.getOutputs().stream().map(x -> x.getName()).collect(Collectors.toList());
            
            for (Output nodeOutput : nodeReactor.getOutputs()) {
                var outputName = nodeOutput.getName();
                if (!outputName.equals(SUCCESS) && !outputName.equals(FAILURE)) {
                    
                    if (nodeOutput.getLocal() == null) {
                        if (!reactorOutputNames.contains(outputName)) {
                            var copyOutput = EcoreUtil.copy(nodeOutput);
                            reactor.getOutputs().add(copyOutput);
                        }
                        var triggers = sequentialOutputs.get(outputName);
                        if (triggers == null) { triggers = new ArrayList<VarRef>(); } 
                        triggers.add(createRef(nodeReactor, instance, outputName));
                        sequentialOutputs.put(outputName, triggers);
                    } else {
                        if (reactorOutputNames.contains(outputName)) {
                            var triggers = sequentialOutputs.get(outputName);
                            if (triggers == null) { triggers = new ArrayList<VarRef>(); } // SOLLTE EIG dann net null sein
                            triggers.add(createRef(nodeReactor, instance, outputName));
                            sequentialOutputs.put(outputName, triggers);
                        } 
                        
                        // was wenn # vorne? finden wir den dann überhaupt hier? JA, weil contains checkt substring
                        var keyName = localCommunication.keySet().stream().filter(x -> x.contains(outputName)).findFirst().orElse(null);
                        TriggerAndEffectRefs triggsAndEffcs = null;
                        if (keyName != null) {
                            triggsAndEffcs = localCommunication.get(keyName);
                        }
                        
                        if (triggsAndEffcs == null) { // TODO: validate: Hier kommt man nur rein, wenn zum ersten mal mit dem comp
                                                      // gearbeitet wird, weil ab dann wird dieser immer ersetzt werden
                                                      // also werden wir für local nie mehr null haben
                                                      // update: WIR WOLLEN DOCH, dass das dann trotzdem rangefügt wird!
                                                      // es werden bei #lock alle trigger rüberkopiert
                            triggsAndEffcs = new TriggerAndEffectRefs();
                            if (reactorInputNames.contains(outputName)) { // HIER WIRD LOCAL AUCH VON DRAUSSEN GENOMMEN (EIG SOLL JA IMMER VALIDATE!!)
                                triggsAndEffcs.triggerRefs.add(createRef(reactor, null, outputName));
                            }
                        } else if (keyName.charAt(0) == '#'){
                            localCompleted.add(triggsAndEffcs);
                            triggsAndEffcs = new TriggerAndEffectRefs();
                            for (var trigger : localCommunication.get(keyName).triggerRefs) {
                                var copyTrigger = EcoreUtil.copy(trigger);
                                triggsAndEffcs.triggerRefs.add(copyTrigger);
                            }
                            localCommunication.remove(keyName);
                            
                        }
                        triggsAndEffcs.triggerRefs.add(createRef(nodeReactor, instance, outputName));
                        localCommunication.put(outputName, triggsAndEffcs);
                    }
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
            
            last.reactor = nodeReactor;
            last.inst = instance;
            i++;
        }
        // if last tasks output success, then sequence will output success
        var connSuccess = createConn(last.reactor, last.inst, SUCCESS,
                reactor, null, SUCCESS);
        reactor.getConnections().add(connSuccess);

        for (var entry : localCommunication.entrySet()) {
            localCompleted.add(entry.getValue());
        }
        
     // LOCALS IN SAME SEQUENCE! change this to generic
        for (var triggsAndEffcs : localCompleted) {
            if (!triggsAndEffcs.effectRefs.isEmpty()) { // TODO überhaupt nötig?
                var reaction = LFF.createReaction();
                
                for (var varref : triggsAndEffcs.effectRefs) {
                    reaction.getEffects().add(varref);
                }
                
                for (var varref : triggsAndEffcs.triggerRefs) {
                    reaction.getTriggers().add(varref);
                }
                
                Collections.reverse(triggsAndEffcs.triggerRefs);
                String codeContent = createLocalOutputCode(triggsAndEffcs);
                var code = LFF.createCode();
                code.setBody(codeContent);
                reaction.setCode(code);
                reactor.getReactions().add(reaction);
                
            }
        }
        
      for (var entry : sequentialOutputs.entrySet()) {
          // WIR WOLLEN, dass das nicht für LOCALS gemacht wird die nicht außen gebraucht werden
          for (var reactorOutput : reactor.getOutputs()) {
              if (entry.getKey().equals(reactorOutput.getName())) {
                  var varrefList = entry.getValue();
                  var reaction = LFF.createReaction();
                  
                  reaction.getEffects().add(createRef(reactor, null, entry.getKey()));
                  
                  for (var varref : varrefList) {
                      reaction.getTriggers().add(varref);
                  }
                  Collections.reverse(varrefList);
                  String codeContent = createOutputCode(varrefList);
                  var code = LFF.createCode();
                  code.setBody(codeContent);
                  reaction.setCode(code);
                  reactor.getReactions().add(reaction);
              }
          }
      }
       
      reactor.getReactions().add(reactionFailure);

        return reactor;
    }
    
    private String createLocalOutputCode(TriggerAndEffectRefs triggsAndEffcs) {
        String result = "";
        String outputName = triggsAndEffcs.triggerRefs.get(0).getVariable().getName();
        String ifOrElseIf = "if(";
        for (var trigger : triggsAndEffcs.triggerRefs) {
            String instNameTrigger = (trigger.getContainer() == null)? "" : trigger.getContainer().getName() + ".";
            result += ifOrElseIf +  instNameTrigger + outputName + "->is_present) {//local\n    ";
            for (var effect : triggsAndEffcs.effectRefs) {
                String instNameEffect = effect.getContainer().getName();
                Type type = null;
                if (effect.getVariable() instanceof Output) { 
                    type = ((Output) effect.getVariable()).getType(); 
                } else if (effect.getVariable() instanceof Input) {
                    type = ((Input) effect.getVariable()).getType();
                }
                String setFunc = (type.getArraySpec() == null) ? "SET(" : "lf_set_array(";  // TODO es gibt bestimmt mehr als nur arrays // TODO und auch für arrays noch net fertig
                result += setFunc + instNameEffect + "." + outputName + ", " + instNameTrigger + outputName + "->value);\n    ";
            }
            result = result.substring(0, result.length()-4) + "}\n";  // DELETE INDENTATION FOR outputSetter
            ifOrElseIf = " else if(";
        }
        return result;
    }

 // Wenn varref kein getter auf variable->type
    private String createOutputCode(ArrayList<VarRef> varrefList) {
        String result = "";
        String outputName = varrefList.get(0).getVariable().getName();
        String ifOrElseIf = "if(";
        for (var varref : varrefList) {
            String instanceName = varref.getContainer().getName();
            Type type = null;
            if (varref.getVariable() instanceof Output) { type = ((Output) varref.getVariable()).getType(); }
            String setFunc = (type.getArraySpec() == null) ? "SET(" : "lf_set_array(";  // TODO es gibt bestimmt mehr als nur arrays
            String settersCode = setFunc + outputName + ", " + instanceName + "." + outputName + "->value);";
            
            result += ifOrElseIf + instanceName + "." + outputName + "->is_present){\n    " + settersCode + "\n}";
            ifOrElseIf = " else if(";
        }
        
        return result;
    }
    
    
    private Reactor transformFallback(Fallback fb, List<Reactor> newReactors, HashMap<BehaviorTreeNode, NodesLocalOutInputs> nodeToLocalOutInputs) {
        var reactor = LFF.createReactor();
        newReactors.add(reactor);
        reactor.setName("Fb" + nodeNameCounter++);
        addBTNodeAnnotation(reactor, NodeType.FALLBACK.toString());

        setBTInterface(reactor);
        
        var reactionSuccess = createMergedOutputReaction(reactor, SUCCESS);

        var seqLocalInOutputs = nodeToLocalOutInputs.remove(fb);
        
        if (seqLocalInOutputs != null) {
            if (!seqLocalInOutputs.inputs.isEmpty()) {
                for (var localToInput : seqLocalInOutputs.inputs) {
                    var input = LFF.createInput();
                    input.setName(localToInput.getName());
                    var copyType = EcoreUtil.copy(localToInput.getType());
                    input.setType(copyType);
                    
                    input.setLocal("true");
                    
                    reactor.getInputs().add(input);
                }
            }
            
            if (!seqLocalInOutputs.outputs.isEmpty()) {
                for (var localToOutput : seqLocalInOutputs.outputs) {
                    var output = LFF.createOutput();
                    output.setName(localToOutput.getName());
                    var copyType = EcoreUtil.copy(localToOutput.getType());
                    output.setType(copyType);
                    
                    output.setLocal("true");
                    
                    reactor.getOutputs().add(output);
                }
            }
        }
        
        int i = 0;
        var last = new ReactorAndInst(reactor, null); // kann wieder lastR und lastIn sein
        var localSenders = new TreeMap<String,TriggerAndEffectRefs>(); // TreeMap um die Reihenfolge zu behalten // <LocalNameAndLock verworfen, weil dann net mehr suche geht (sonst zweite HashMap) TODO besprechen
        var localReactions = new ArrayList<TriggerAndEffectRefs>();
       var sequentialOutputs = new HashMap<String, ArrayList<VarRef>>();
        for (var node : fb.getNodes()) {
            var nodeReactor = transformNode(node, newReactors, nodeToLocalOutInputs);
            
            // instantiate child
            var instance = LFF.createInstantiation();
            instance.setReactorClass(nodeReactor);
            instance.setName(nodeReactor.getName() + "_i");
            reactor.getInstantiations().add(instance);
            
         // add all inputs of childs to own inputs
         // TODO connect locals
            // add all inputs of childs to own inputs TODO if they are not locals
            var reactorInputNames = reactor.getInputs().stream().map(x -> x.getName()).collect(Collectors.toList());
            
            // COPY NEEDED INPUTS
            for (Input nodeInput : nodeReactor.getInputs()) {
                var inputName = nodeInput.getName();
                if (!inputName.equals(START)) {
                    
                    if (reactorInputNames.contains(inputName)) {
                        var inputConn = createConn(reactor, null, inputName, nodeReactor, instance, inputName);
                        reactor.getConnections().add(inputConn);
                    } else if (nodeInput.getLocal() == null) {
                        if (!reactorInputNames.contains(inputName)) {
                            var copyInput = EcoreUtil.copy(nodeInput);
                            reactor.getInputs().add(copyInput);
                        }
                        var inputConn = createConn(reactor, null, inputName, nodeReactor, instance, inputName);
                        reactor.getConnections().add(inputConn);
                    } else {
                        
                        var keyName = localSenders.keySet().stream().filter(x -> x.contains(inputName)).findFirst().orElse(null);
                        TriggerAndEffectRefs triggsAndEffcs = null;
                        if (keyName != null) {
                            triggsAndEffcs = localSenders.get(keyName);
                        }
                        
                        if (triggsAndEffcs == null) {
                            // What to do here when Task versucht ein Local zu lesen welche noch nie gesendet
                            // DAS HEIßt Local von außen holen!!! DONE
                            
                            // EIG WÜRDE ES HIER NIEMALS DAZU KOMMMEN, WEIL WIR OBEN da stehen
                            // Haben dass dann conn gemacht werden muss, und deshlab kommen wir 
                            // hier nie rein weil else if ist.
                           } else {
                               String localNameWriteLock = keyName;
                               if (keyName.charAt(0) != '#') {
                                   localSenders.remove(keyName);
                                   localNameWriteLock = "#" + keyName;
                               }
                               triggsAndEffcs.effectRefs.add(createRef(nodeReactor, instance, nodeInput.getName()));
                               localSenders.put(localNameWriteLock, triggsAndEffcs);
                           }
                    }
               }
                
            }
            
            // add all inputs of childs to own inputs // make this a method (mit option zwischen input output) TODO
            var reactorOutputNames = reactor.getOutputs().stream().map(x -> x.getName()).collect(Collectors.toList());
            
            for (Output nodeOutput : nodeReactor.getOutputs()) {
                var outputName = nodeOutput.getName();
                if (!outputName.equals(SUCCESS) && !outputName.equals(FAILURE)) {
                    if (reactorOutputNames.contains(outputName)) {
                        var triggers = sequentialOutputs.get(outputName);
                        if (triggers == null) { triggers = new ArrayList<VarRef>(); } 
                        triggers.add(createRef(nodeReactor, instance, outputName));
                        sequentialOutputs.put(outputName, triggers);
                    } else if (nodeOutput.getLocal() == null) {
                        if (!reactorOutputNames.contains(outputName)) {
                            var copyOutput = EcoreUtil.copy(nodeOutput);
                            reactor.getOutputs().add(copyOutput);
                        }
                        var triggers = sequentialOutputs.get(outputName);
                        if (triggers == null) { triggers = new ArrayList<VarRef>(); } 
                        triggers.add(createRef(nodeReactor, instance, outputName));
                        sequentialOutputs.put(outputName, triggers);
                    } else {
                        var keyName = localSenders.keySet().stream().filter(x -> x.contains(outputName)).findFirst().orElse(null);
                        TriggerAndEffectRefs triggsAndEffcs = null;
                        if (keyName != null) {
                            triggsAndEffcs = localSenders.get(keyName);
                        }
                        
                        if (triggsAndEffcs == null) {
                            triggsAndEffcs = new TriggerAndEffectRefs();
                        } else if (keyName.charAt(0) == '#'){
                            localReactions.add(triggsAndEffcs);
                            triggsAndEffcs = new TriggerAndEffectRefs();
                            for (var trigger : localSenders.get(keyName).triggerRefs) {
                                var copyTrigger = EcoreUtil.copy(trigger);
                                triggsAndEffcs.triggerRefs.add(copyTrigger);
                            }
                            localSenders.remove(keyName);
                            
                        }
                        triggsAndEffcs.triggerRefs.add(createRef(nodeReactor, instance, outputName));
                        localSenders.put(outputName, triggsAndEffcs);
                   }
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
                var connForward = createConn(last.reactor, last.inst,
                        FAILURE, nodeReactor, instance, START);
                reactor.getConnections().add(connForward);
            }
            
            
            last.reactor = nodeReactor;
            last.inst = instance;
            i++;
        }
        // if last tasks output failure, then fallback will output failure
        var connFailure = createConn(last.reactor, last.inst, FAILURE,
                reactor, null, FAILURE);
        reactor.getConnections().add(connFailure);

        for (var entry : localSenders.entrySet()) {
            localReactions.add(entry.getValue());
        }
        
     // LOCALS IN SAME SEQUENCE! change this to generic
        for (var triggsAndEffcs : localReactions) {
            if (!triggsAndEffcs.effectRefs.isEmpty()) { // TODO überhaupt nötig?
                var reaction = LFF.createReaction();
                
                for (var varref : triggsAndEffcs.effectRefs) {
                    reaction.getEffects().add(varref);
                }
                
                for (var varref : triggsAndEffcs.triggerRefs) {
                    reaction.getTriggers().add(varref);
                }
                
                Collections.reverse(triggsAndEffcs.triggerRefs);
                String codeContent = createLocalOutputCode(triggsAndEffcs);
                var code = LFF.createCode();
                code.setBody(codeContent);
                reaction.setCode(code);
                reactor.getReactions().add(reaction);
                
            }
        }
        
      for (var entry : sequentialOutputs.entrySet()) {
          // WIR WOLLEN, dass das nicht für LOCALS gemacht wird die nicht außen gebraucht werden
          for (var reactorOutput : reactor.getOutputs()) {
              if (entry.getKey().equals(reactorOutput.getName())) {
                  var varrefList = entry.getValue();
                  var reaction = LFF.createReaction();
                  
                  reaction.getEffects().add(createRef(reactor, null, entry.getKey()));
                  
                  for (var varref : varrefList) {
                      reaction.getTriggers().add(varref);
                  }
                  Collections.reverse(varrefList);
                  String codeContent = createOutputCode(varrefList);
                  var code = LFF.createCode();
                  code.setBody(codeContent);
                  reaction.setCode(code);
                  reactor.getReactions().add(reaction);
              }
          }
      }
       
      reactor.getReactions().add(reactionSuccess);

        return reactor;
    }
    
    
    private Reactor transformParallel(Parallel par, List<Reactor> newReactors, HashMap<BehaviorTreeNode, NodesLocalOutInputs> nodeToLocalOutInputs) {
        int M = (par.getM() != 0)? par.getM() : par.getNodes().size();  // TODO hier sollte noch 0 support rein
        var reactor = LFF.createReactor();
        newReactors.add(reactor);
        reactor.setName("Par" + nodeNameCounter++);
        addBTNodeAnnotation(reactor, NodeType.PARALLEL.toString());

        setBTInterface(reactor);
        
        var mergedOutputReaction = LFF.createReaction(); 
        
        for (var node : par.getNodes()) {
            var nodeReactor = transformNode(node, newReactors, nodeToLocalOutInputs);
            
         // instantiate child
            var instance = LFF.createInstantiation();
            instance.setReactorClass(nodeReactor);
            instance.setName(nodeReactor.getName() + "_i");
            reactor.getInstantiations().add(instance);
            
            mergedOutputReaction.getTriggers().add(createRef(nodeReactor, instance, SUCCESS));
            mergedOutputReaction.getTriggers().add(createRef(nodeReactor, instance, FAILURE));
            reactor.getConnections().add(createConn(reactor, null, START, nodeReactor, instance, START));
        }
        setMergedOutputReactionForPar(mergedOutputReaction, par.getNodes().size(), M, reactor);
        
        reactor.getReactions().add(mergedOutputReaction);
        return reactor;
    }
    
    private void setMergedOutputReactionForPar(Reaction reaction, int N, int M, Reactor reactor) {
        var code = LFF.createCode();
        String codeContent = "int successCounter = 0;\n"
                + "int failureCounter = 0;\n\n";
        for (var succTrigger : reaction.getTriggers()) {
            var triggerVarRef = (VarRef) succTrigger;
            String instName = triggerVarRef.getContainer().getName();
            
            if (triggerVarRef.getVariable().getName().equals(SUCCESS)) {
                codeContent += "if(" + instName + ".success->is_present) {\n    "
                        + "successCounter++;\n}\n";
            } else {
                codeContent += "if(" + instName + ".failure->is_present) {\n    "
                        + "failureCounter++;\n}\n";
            }
            
        }
        codeContent += "\nif(successCounter >= " + M + ") {\n    "
                + "SET(success, true);\n} "
                + "else if (failureCounter > " + (N-M) + ") {\n    "
                + "SET(failure, true);\n}";
        
        
        code.setBody(codeContent);
        reaction.setCode(code);
        reaction.getEffects().add(createRef(reactor, null, SUCCESS));
        reaction.getEffects().add(createRef(reactor, null, FAILURE));
    }
    
    private Reaction createMergedOutputReaction(Reactor reactor, String outputName) {
        var reaction = LFF.createReaction();
        var code = LFF.createCode();
        code.setBody("SET(" + outputName + ", true);");
        reaction.setCode(code);
        reaction.getEffects().add(createRef(reactor, null, outputName));
        
        return reaction;
    }
    
    private Reactor transformTask(Task task, List<Reactor> newReactors, HashMap<BehaviorTreeNode, NodesLocalOutInputs> nodeToLocalOutInputs) {
        var reactor = LFF.createReactor();
        newReactors.add(reactor);
        // TODO richtige benennung von allen task, seq, fb
        String nameOfTask = task.getTaskName() == null ? 
                                "Task" + (nodeNameCounter++) :
                                 task.getTaskName();
        reactor.setName(nameOfTask);
        
        String btNodeAnnot = task.isCondition() ?
                               NodeType.CONDITION.toString() :
                               NodeType.ACTION.toString();
        addBTNodeAnnotation(reactor, btNodeAnnot);
        
        setBTInterface(reactor);
        
        for (VarRef varref : task.getTaskSources()) {
          if (varref.getVariable() instanceof Input) {
              var copyInput = EcoreUtil.copy(((Input) varref.getVariable()));
              reactor.getInputs().add(copyInput);
          }
        }
        for (VarRef varref : task.getTaskEffects()) {
            if (varref.getVariable() instanceof Output) {
                var copyOutput = EcoreUtil.copy(((Output) varref.getVariable()));
                reactor.getOutputs().add(copyOutput);
            }
        }
        var tasksLocalInOutputs = nodeToLocalOutInputs.remove(task);
        
        if (tasksLocalInOutputs != null) {
            if (!tasksLocalInOutputs.inputs.isEmpty()) {
                for (var localToInput : tasksLocalInOutputs.inputs) {
                    var input = LFF.createInput();
                    input.setName(localToInput.getName());
                    var copyType = EcoreUtil.copy(localToInput.getType());
                    input.setType(copyType);
                    
                    input.setLocal("true");
                    
                    reactor.getInputs().add(input);
                }
            }
            
            if (!tasksLocalInOutputs.outputs.isEmpty()) {
                for (var localToOutput : tasksLocalInOutputs.outputs) {
                    var output = LFF.createOutput();
                    output.setName(localToOutput.getName());
                    var copyType = EcoreUtil.copy(localToOutput.getType());
                    output.setType(copyType);
                    
                    output.setLocal("true");
                    
                    reactor.getOutputs().add(output);
                }
            }
        }
        
        var reaction = LFF.createReaction();
        if (task.getCode() != null) {
//            reaction.setCode(task.getCode()); makes code go null
            var copyCode = EcoreUtil.copy(task.getCode());
            reaction.setCode(copyCode);
        }
        reaction.getTriggers().add(createRef(reactor, null, START));
        reaction.getEffects().add(createRef(reactor, null, SUCCESS));
        reaction.getEffects().add(createRef(reactor, null, FAILURE));

        for (var input : reactor.getInputs()) {
            if (!input.getName().equals(START))  // TODO CHANGED
            reaction.getSources().add(createRef(reactor, null, input.getName()));
        }
        
        for (var output : reactor.getOutputs()) {
            if (!output.getName().equals(SUCCESS) && !output.getName().equals(FAILURE))  // TODO CHANGED
            reaction.getEffects().add(createRef(reactor, null, output.getName()));
        }

        reactor.getReactions().add(reaction);
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
    class ReactorAndInst { // kann man eig wieder entfernen weil localSenders weg ist
        Reactor reactor = null;
        Instantiation inst = null;
        
        private ReactorAndInst(Reactor reactor, Instantiation instantiation) {
            this.reactor = reactor;
            this.inst = instantiation;
        }
    }
    class TriggerAndEffectRefs {
        ArrayList<VarRef> triggerRefs = new ArrayList<VarRef>();
        ArrayList<VarRef> effectRefs = new ArrayList<VarRef>();;
    }
    class NodesLocalOutInputs {
        HashSet<Local> outputs = new HashSet<Local>();
        HashSet<Local> inputs = new HashSet<Local>();
    }
    class LocalsPotentialOutInputter {
        HashMap<Local, List<String>> outDep = new HashMap<>();
        HashMap<Local, List<String>> inDep = new HashMap<>();
    }
    
    // Wird nicht gehen weil dann können wir HashMap nicht mehr über String name durchgehen
//    class LocalNameAndWriteLock {
//        String name;
//        boolean writeLock;
//        
//        private LocalNameAndWriteLock (String name, boolean writeLock) {
//            this.name = name;
//            this.writeLock = writeLock;
//        }
//    }
//    class ReactorLinkParent {
//        
//        Reactor reactor = null;
//        ReactorLinkParent link = null;
//        
//        private ReactorLinkParent (Reactor reactor, ReactorLinkParent link) {
//            this.reactor = reactor;
//            this.link = link;
//        }
//    }
}
