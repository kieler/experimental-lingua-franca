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
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
        copyInOutputs(reactor, bt.getInputs(), bt.getOutputs());
        
        
        resolveLocalDependencies(bt);
        
        // Transform BT root
        var deleteThis = bt.getRootNode(); // makes debugging easier (temporary)
        var nodeReactor = transformNode(deleteThis, newReactors, reactor);
        var instance = LFF.createInstantiation();
        instance.setReactorClass(nodeReactor); // WICHTIG? wofür?
        instance.setName("root");
        reactor.getInstantiations().add(instance);

        // forward in and outputs of mock reactor to BT root node
        connectInOutputs(reactor, nodeReactor, instance); // TODO problem: leere parallel kriegt kein bt implicit interface (ist im scope provider)
        
        return reactor;
    }
    
    private void copyInOutputs (Reactor reactor, List<Input> inputs, List<Output> outputs) {
        for (Input input : inputs) {
            var copyInput = EcoreUtil.copy(input);
            reactor.getInputs().add(copyInput);
        }
        
        for (Output output : outputs) {
            var copyOutput = EcoreUtil.copy(output);
            reactor.getOutputs().add(copyOutput);
        }
    }
    
    private void connectInOutputs(Reactor reactor, Reactor childReactor,
            Instantiation instance) {
        // TODO fun mapping?
        var childInputNames = new ArrayList<String>();
        for (Input inputChild : childReactor.getInputs()) {
            childInputNames.add(inputChild.getName());
        }
        
        for (Input in : reactor.getInputs()) {
            if (childInputNames.contains(in.getName())) {
                var conn = createConn(reactor, null, in.getName(), childReactor, instance, in.getName());
                reactor.getConnections().add(conn);                
            }
        }
        
        var childOutputNames = new ArrayList<String>();
        for (Output outputChild : childReactor.getOutputs()) {
            childOutputNames.add(outputChild.getName());
        }

        for (Output out : reactor.getOutputs()) {
            if (childOutputNames.contains(out.getName())) {
                var conn = createConn(childReactor, instance, out.getName(), reactor, null, out.getName());
                reactor.getConnections().add(conn);
                
            }
        }

    }
    
    private void resolveLocalDependencies(BehaviorTree bt) {
        if (bt.getRootNode() instanceof Parallel) return; // TODO was tun hier fragen
        if (bt.getRootNode() instanceof Task) return;
        
        var dependencies = new HashMap<String, List<String>>();
        getPotentialLocalInputs(bt.getRootNode(), "0", dependencies);
        
        int a = 0;
        int b = 0;
    }
    
    private void getPotentialLocalInputs(BehaviorTreeNode seqOrFb, String path, HashMap<String, List<String>> result) {
        Sequence seq = null;
        Fallback fb = null;
        if (seqOrFb instanceof Sequence) seq = (Sequence) seqOrFb;
        if (seqOrFb instanceof Fallback) fb = (Fallback) seqOrFb;
        
        int i = 0;
        if (seq != null) {
            for (var node : seq.getNodes()) {
                if (node instanceof Task) {
                    for (var varref : ((Task) node).getTaskSources()) {
                        if (varref.getVariable() instanceof Local) {
                            String inputName = varref.getVariable().getName();
                            var pathList = result.get(inputName);
                            if (pathList == null) {
                                pathList = new ArrayList<String>();
                            }
                            pathList.add(path + i);
                            result.put(inputName, pathList);
                        }
                    }
                } else if (node instanceof Sequence || node instanceof Fallback) {
                    getPotentialLocalInputs(node, path + i, result);
                }
                i++;
            }
        } else if (fb != null) {
            for (var node : fb.getNodes()) {
                if (node instanceof Task) {
                    for (var varref : ((Task) node).getTaskSources()) {
                        if (varref.getVariable() instanceof Local) {
                            String inputName = varref.getVariable().getName();
                            var pathList = result.get(inputName);
                            if (pathList == null) {
                                pathList = new ArrayList<String>();
                            }
                            pathList.add(path + i);
                            result.put(inputName, pathList);
                        }
                    }
                } else if (node instanceof Sequence || node instanceof Fallback) {
                    getPotentialLocalInputs(node, path + i, result);
                }
                i++;
            }
        }
            
    }

    private Reactor transformNode(BehaviorTreeNode node, List<Reactor> newReactors, Reactor rootReactor) {
        if (node instanceof Sequence) {
            return transformSequence((Sequence) node, newReactors, rootReactor);
        } else if (node instanceof Task) {
            return transformTask((Task) node, newReactors, rootReactor);
        } else if (node instanceof Fallback) {
            return transformFallback((Fallback) node, newReactors, rootReactor);
        } else if (node instanceof Parallel) {
            return transformParallel((Parallel) node, newReactors, rootReactor);
        }
        return null;
    }

    private Reactor transformSequence(Sequence seq, List<Reactor> newReactors, Reactor rootReactor) {
        // TODO LÖSUNG FÜR BESSERE PERFORMANCE: LISTE MIT INPUTS UND OUTPUTS    var inputs = new ArrayList<Input>();
        var reactor = LFF.createReactor();
        newReactors.add(reactor);
//        boolean sequenceIsRoot = nodeNameCounter == 0;  SOLL NICHT NÖTIG SEIN!
        reactor.setName("Seq" + nodeNameCounter++);
        addBTNodeAnnotation(reactor, NodeType.SEQUENCE.toString());

        setBTInterface(reactor);
        
        // reaction will output failure, if any child produces failure
        var reactionFailure = createMergedOutputReaction(reactor, FAILURE);

        int i = 0;
        var last = new ReactorAndInst(reactor, null); // kann wieder lastR und lastIn sein
        // Mb rechte seite direkt reaction machen
        var localSenders = new TreeMap<String,TriggerAndEffectRefs>(); // TreeMap um die Reihenfolge zu behalten // <LocalNameAndLock verworfen, weil dann net mehr suche geht (sonst zweite HashMap) TODO besprechen
        var localReactions = new ArrayList<TriggerAndEffectRefs>();
        var sequentialOutputs = new HashMap<String, ArrayList<VarRef>>();
//        potentialLocalOutputs = new HashMap<String, BehaviorTreeNode>();  PROBLEM: WIE komm ich an diese vom nested sequence
        for (var node : seq.getNodes()) {
            var nodeReactor = transformNode(node, newReactors, rootReactor);
            
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
                        
                        var keyName = localSenders.keySet().stream().filter(x -> x.contains(inputName)).findFirst().orElse(null);
                        TriggerAndEffectRefs triggsAndEffcs = null;
                        if (keyName != null) {
                            triggsAndEffcs = localSenders.get(keyName);
                        }
                        
                        if (triggsAndEffcs == null) {
                            // What to do here when Task versucht ein Local zu lesen welche noch nie gesendet
                            // DAS HEIßt Local von außen holen!!! TODO
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
                    
                     // TODO was wenn einer ausversehen einen task zuerst auf einen noch net geschriebenen local liest
                }
            }
            // add all inputs of childs to own inputs // make this a method (mit option zwischen input output) TODO
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
//                            triggsAndEffcs.triggerRefs.addAll(localSenders.get(keyName).triggerRefs); KRASSER BUG
                            localSenders.remove(keyName);
                            
                        }
                        triggsAndEffcs.triggerRefs.add(createRef(nodeReactor, instance, outputName));
                        localSenders.put(outputName, triggsAndEffcs);
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
            
            // TODO .getLocal() zu Bool machen.
            // Map<localName, Reactor>
            
            // FOR LOCALS IN SAME SEQUENCES
//            for (Output nodeOutput : nodeReactor.getOutputs()) {
//                if (nodeOutput.getLocal() != null) {
//                    var triggsAndEffcs = localSenders.get(nodeOutput.getName());
//                    if (triggsAndEffcs == null) {
//                        triggsAndEffcs = new TriggerAndEffectRefs();
//                        triggsAndEffcs.triggerRefs.add(createRef(nodeReactor, instance, nodeOutput.getName()));
//                        localSenders.put(nodeOutput.getName(), triggsAndEffcs);
//                    } else {
//                        triggsAndEffcs.triggerRefs.add(createRef(nodeReactor, instance, nodeOutput.getName()));
//                        localSenders.put(nodeOutput.getName(), triggsAndEffcs);
//                    }
//                }
//            }
//            for (Input nodeInput : nodeReactor.getInputs()) {
//                if (nodeInput.getLocal() != null) {
////                    boolean localSenderIsInSameSequence = localSenders.get(nodeInput.getName()) != null;
//                    var triggsAndEffcs = localSenders.get(nodeInput.getName());
//                    if (triggsAndEffcs == null) {
//                        int a = 10;
//                        int b = 20;
//                        // kann net sein, weil d.h. dass der local Input gelesen wird, bevor er geschrieben wurde TODO
//                        // NEIN KANN SEIN, S.h. LocalScopeTest
//                    } else {
//                        triggsAndEffcs.effectRefs.add(createRef(nodeReactor, instance, nodeInput.getName()));
//                        localSenders.put(nodeInput.getName(), triggsAndEffcs);
//                    }
//                    
//                }
//            }
            
            last.reactor = nodeReactor;
            last.inst = instance;
            i++;
        }
        // if last tasks output success, then sequence will output success
        var connSuccess = createConn(last.reactor, last.inst, SUCCESS,
                reactor, null, SUCCESS);
        reactor.getConnections().add(connSuccess);

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
        
        // LOCALS IN SAME SEQUENCE! change this to generic
//        for (var entry : localSenders.entrySet()) {
//            if (!entry.getValue().effectRefs.isEmpty()) {
//                var triggsAndEffcs = entry.getValue();
//                var reaction = LFF.createReaction();
//                
////            var reactorOutputNames = reactor.getOutputs().stream().map(x -> x.getName()).collect(Collectors.toList());
////            if (reactorOutputNames.contains(entry.getKey())) {
////                var seqVarref = createRef(reactor, null, entry.getKey());
////                reaction.getEffects().add(seqVarref);
//////                entry.getValue().effectRefs.add(seqVarref);
////            }
//                
//                for (var varref : entry.getValue().effectRefs) {
//                    reaction.getEffects().add(varref);
//                }
//                
//                for (var varref : entry.getValue().triggerRefs) {
//                    reaction.getTriggers().add(varref);
//                }
//                
//                Collections.reverse(triggsAndEffcs.triggerRefs);
//                String codeContent = createLocalOutputCode(triggsAndEffcs);
//                var code = LFF.createCode();
//                code.setBody(codeContent);
//                reaction.setCode(code);
//                reactor.getReactions().add(reaction);
//            }
//        }
        
        
//        for (var outputEntry : potentialLocalOutputs.entrySet()) {
//            for (var inputEntry : potentialLocalInputs.entrySet()) {
//                var inputName = inputEntry.getKey().getName();
//                
//                if (outputEntry.getKey().getName().equals(inputName)) {
//                    for (var inputReactor : inputEntry.getValue()) {
//                        var parentReactor = linksToParent.get(inputReactor).reactor;
//                        var inputReactorInst = linksToParent.get(inputReactor).inst;
//                        if (parentReactor.getInputs().stream().noneMatch(x -> x.getName().equals(inputName))) {
//                            var copyInput = EcoreUtil.copy(inputEntry.getKey());
//                            parentReactor.getInputs().add(copyInput);
//                        }
//                        var conn = createConn(parentReactor, null, inputName, inputReactor, inputReactorInst, inputName);
//                        parentReactor.getConnections().add(conn);
//                        
//                        var replaceReactorByParent =  new ArrayList<Reactor>(inputEntry.getValue());
//                        replaceReactorByParent.remove(inputReactor);
//                        replaceReactorByParent.add(parentReactor);
//                        potentialLocalInputs.put(inputEntry.getKey(), replaceReactorByParent);
//                    }
//                }
//            }
//        }
        
//        // FOR LOCALS NOT IN SAME SEQUENCE    // MB NUR EIN SCHRITT BACK NÖTIG, weil vorherige sequence es eh macht
//        for (var outputEntry : potentialLocalOutputs.entrySet()) {
//            for (var inputEntry : potentialLocalInputs.entrySet()) {
//                var inputName = inputEntry.getKey().getName();
//                if (outputEntry.getKey().getName().equals(inputName)) { //                    for (var conn : )                                       // LIEBER SO MACHEN, dass man nicht conn erstellt tbh!
//                    // create Inputs for all parents until current seq and conn them
//                    var inputReactors = inputEntry.getValue();
//                    for (var inputReactor : inputReactors) {
//                        var parentReactor = linksToParent.get(inputReactor).reactor;
//                        var inputReactorInst = linksToParent.get(inputReactor).inst;
//                        while (!parentReactor.equals(reactor)) {
//                            if (parentReactor.getInputs().stream().noneMatch(x -> x.getName().equals(inputName))) {
//                                var copyInput = EcoreUtil.copy(inputEntry.getKey());
//                                parentReactor.getInputs().add(copyInput);
//                            }
//                            var conn = createConn(parentReactor, null, inputName, inputReactor, inputReactorInst, inputName);
//                            parentReactor.getConnections().add(conn);
//                            parentReactor = linksToParent.get(parentReactor).reactor;
//                        }
//                    }
//                }
//            }
//        }
       
        
      for (var entry : sequentialOutputs.entrySet()) {
          // WIR WOLLEN, dass das nicht für LOCALS gemacht wird die nicht außen gebraucht werden
          for (var reactorOutput : reactor.getOutputs()) {
              if (entry.getKey().equals(reactorOutput.getName())) {
                  var varrefList = entry.getValue();
                  var reaction = LFF.createReaction();
                  
                  reaction.getEffects().add(createRef(reactor, null, entry.getKey()));
                  
                  // TODO mb überall var hinmachen bei for statement
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
            String instNameTrigger = trigger.getContainer().getName();
            result += ifOrElseIf +  instNameTrigger + "." + outputName + "->is_present) {//local\n    ";
            for (var effect : triggsAndEffcs.effectRefs) {
                String instNameEffect = effect.getContainer().getName();
                Type type = null;
                if (effect.getVariable() instanceof Output) { 
                    type = ((Output) effect.getVariable()).getType(); 
                } else if (effect.getVariable() instanceof Input) {
                    type = ((Input) effect.getVariable()).getType();
                }
                String setFunc = (type.getArraySpec() == null) ? "SET(" : "lf_set_array(";  // TODO es gibt bestimmt mehr als nur arrays // TODO und auch für arrays noch net fertig
                result += setFunc + instNameEffect + "." + outputName + ", " + instNameTrigger + "." + outputName + "->value);\n    ";
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
    
    private Reactor transformFallback(Fallback fb, List<Reactor> newReactors, Reactor rootReactor) {
        var reactor = LFF.createReactor();
        newReactors.add(reactor);
        reactor.setName("Fallback" + nodeNameCounter++);
        addBTNodeAnnotation(reactor, NodeType.FALLBACK.toString());

        setBTInterface(reactor);

        // reaction will output success, if any child produces success
        Reaction reactionSuccess = createMergedOutputReaction(reactor, SUCCESS);

        int i = 0;
        var last = new ReactorAndInst(reactor, null);
        var localSenders = new HashMap<String,ReactorAndInst>();
        var sequentialOutputs = new HashMap<String, ArrayList<VarRef>>();
        for (var node : fb.getNodes()) {
            var nodeReactor = transformNode(node, newReactors, rootReactor);
            
            // instantiate child
            var instance = LFF.createInstantiation();
            instance.setReactorClass(nodeReactor);
            instance.setName("inst" + fb.getNodes().indexOf(node));
            reactor.getInstantiations().add(instance);
            
         // add all inputs of childs to own inputs
         // TODO connect locals
            // add all inputs of childs to own inputs TODO if they are not locals
            var reactorInputNames = reactor.getInputs().stream().map(x -> x.getName()).collect(Collectors.toList());
            
            // COPY NEEDED INPUTS
            for (Input nodeInput : nodeReactor.getInputs()) {
                
//                boolean isLocal = rootInput.getLocal() != null;
                boolean isInSameSequence = localSenders.containsKey(nodeInput.getName());                
                
                if (!reactorInputNames.contains(nodeInput.getName()) 
                        && !isInSameSequence) { // TODO ineffizient, mb: liste, die dann am ende added wird
                    var copyInput = EcoreUtil.copy(nodeInput);
                    reactor.getInputs().add(copyInput);
                }
                // Connect the inputs
                if (!nodeInput.getName().equals(START) && !isInSameSequence) {
                    var inputConn = createConn(reactor, null, nodeInput.getName(), nodeReactor, instance, nodeInput.getName());
                    reactor.getConnections().add(inputConn);
                }
                
            }
            
            // add all inputs of childs to own inputs // make this a method (mit option zwischen input output) TODO
            var reactorOutputNames = reactor.getOutputs().stream().map(x -> x.getName()).collect(Collectors.toList());
            
            for (Output nodeOutput : nodeReactor.getOutputs()) {
                
                boolean isInSameSequence = localSenders.containsKey(nodeOutput.getName());

//                if (!reactorOutputNames.contains(nodeOutput.getName()) && !isInSameSequence) { //TODO ineffizient, mb: liste, die dann am ende added wird
//                    var copyOutput = EcoreUtil.copy(nodeOutput);
//                    reactor.getOutputs().add(copyOutput);
//                }
                // Connect the Output
//                if (!nodeOutput.getName().equals(SUCCESS) && !nodeOutput.getName().equals(FAILURE) && !isInSameSequence) {
//                    var outputConn = createConn(nodeReactor, instance, nodeOutput.getName(), reactor, null, nodeOutput.getName());
//                    reactor.getConnections().add(outputConn);
//                }
                
                if (!nodeOutput.getName().equals(SUCCESS) && !nodeOutput.getName().equals(FAILURE)) {
                    if (!reactorOutputNames.contains(nodeOutput.getName()) && !isInSameSequence) { // TODO ineffizient
                        var copyOutput = EcoreUtil.copy(nodeOutput);
                        reactor.getOutputs().add(copyOutput);
                    }
                    
                    var varrefList = sequentialOutputs.get(nodeOutput.getName());
                    if (varrefList == null) {
                        varrefList = new ArrayList<VarRef>();
                        varrefList.add(createRef(nodeReactor, instance, nodeOutput.getName()));
                        sequentialOutputs.put(nodeOutput.getName(), varrefList);
                    } else {
                        varrefList.add(createRef(nodeReactor, instance, nodeOutput.getName()));
                        sequentialOutputs.put(nodeOutput.getName(), varrefList);
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
            
            // HOW TO DO CONNECTIONS? TODO: was tun wenn mehrere gleichen local haben?
            // TODO .getLocal() zu Bool machen.
//             Map<Reactor, Instantiation> NEIN LIEBER ALS CLASS
            // Map<localName, Reactor>
            
            // FOR LOCALS IN SAME SEQUENCES
            for (Output nodeOutput : nodeReactor.getOutputs()) {
                var localSender = new ReactorAndInst(nodeReactor, instance);
                if (nodeOutput.getLocal() != null && nodeOutput.getLocal().equals("true")) {
                    localSenders.put(nodeOutput.getName(), localSender);
                }
            }
            for (Input nodeInput : nodeReactor.getInputs()) {
                boolean localSenderIsInSameSequence = localSenders.get(nodeInput.getName()) != null;
                // mb noch if (input.getLocal() != null ? Oder ist eh automatisch auch local dann
                if (localSenderIsInSameSequence) {
                    var localSender = localSenders.get(nodeInput.getName());
                    var conn = createConn(localSender.reactor, localSender.inst, nodeInput.getName(), nodeReactor, instance, nodeInput.getName());
                    reactor.getConnections().add(conn);
                }
            }
            // for Locals NOT in same Sequence
            // Nothing to do here? TODO recheck
            
            last.reactor = nodeReactor;
            last.inst = instance;
            i++;
        }
        // if last tasks output failure, then fallback will output failure
        var connFailure = createConn(last.reactor, last.inst, FAILURE,
                reactor, null, FAILURE);
        reactor.getConnections().add(connFailure);

        reactor.getReactions().add(reactionSuccess);

        for (var entry : sequentialOutputs.entrySet()) {
            var varrefList = entry.getValue();
            var reaction = LFF.createReaction();

            var effect = createRef(reactor, null, entry.getKey());// TODO check ob man das einfachn unten reinmachen kann 
            reaction.getEffects().add(effect);

            // TODO mb überall var hinmachen bei for statement
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
        

        return reactor;
    }
    
    private Reactor transformParallel(Parallel par, List<Reactor> newReactors, Reactor rootReactor) {
        var reactor = LFF.createReactor();
        newReactors.add(reactor);
        reactor.setName("Par" + nodeNameCounter++);
        addBTNodeAnnotation(reactor, NodeType.PARALLEL.toString());

        setBTInterface(reactor);
        
        var mergedOutputReaction = LFF.createReaction(); 
        
        for (var node : par.getNodes()) {
            var nodeReactor = transformNode(node, newReactors, rootReactor);
            
         // instantiate child
            var instance = LFF.createInstantiation();
            instance.setReactorClass(nodeReactor);
            instance.setName(nodeReactor.getName() + "_i");
            reactor.getInstantiations().add(instance);
            
            mergedOutputReaction.getTriggers().add(createRef(nodeReactor, instance, SUCCESS));
            mergedOutputReaction.getTriggers().add(createRef(nodeReactor, instance, FAILURE)); // NÖTIG: weil wenn alle returnen failure, dann wird reaction net ausgelöst
                                                                                               // würde ja eig auch reichen, wenn nur ein child sich verbindet... TODO fragen
            reactor.getConnections().add(createConn(reactor, null, START, nodeReactor, instance, START));
        }
        setMergedOutputReactionForPar(mergedOutputReaction, par.getM(), reactor);
        
        reactor.getReactions().add(mergedOutputReaction);
        return reactor;
    }
    
    private void setMergedOutputReactionForPar(Reaction reaction, int M, Reactor reactor) {
        var code = LFF.createCode();
        String codeContent = "int successCounter = 0;\n";
        for (var succTrigger : reaction.getTriggers()) {
            var triggerVarRef = (VarRef) succTrigger;
            if (triggerVarRef.getVariable().getName().equals(SUCCESS)) {
                String instName = triggerVarRef.getContainer().getName();
                codeContent += "if(" + instName + ".success->is_present) {\n    "
                        + "successCounter++;\n}\n";
            }
            
        }
        codeContent += "if(successCounter >= " + M + ") {\n    "
                + "SET(success, true);\n} else {\n    "
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
            for (Output rootOutput : rootReactor.getOutputs()) { // GAR NICHT NÖTIG SINCE VARREF.get VAR ZU OUTPUT CASTEN TODO
                if (varref.getVariable().getName().equals(rootOutput.getName())) {   //TODO ineffizient
                    // copy the input (wenn cpy net geht mach wie bei setBTInterface)
                    var copyOutput = EcoreUtil.copy(rootOutput);
                    reactor.getOutputs().add(copyOutput);
                }
            }
        }
        
        // TODO ineffizient, gib Task eine Liste mit allen LOCALS!
        // set local outputs
        if (!(task.eContainer() instanceof BehaviorTree)) { // nötig weil nur dann gehen wir hier durch
            var allLocals = new ArrayList<Local>();
            var seqOrFbOrPar = task.eContainer();
            while (!(seqOrFbOrPar instanceof BehaviorTree)) {
                if (seqOrFbOrPar instanceof Sequence) {
                    allLocals.addAll(((Sequence) seqOrFbOrPar).getLocals());
                } else if (seqOrFbOrPar instanceof Fallback) {
                    allLocals.addAll(((Fallback) seqOrFbOrPar).getLocals());
                } else {
                    // TODO WAS TUN FÜR PARs??
                }
                seqOrFbOrPar = seqOrFbOrPar.eContainer();
            }
            for (VarRef varref : task.getTaskEffects()) {
                if (varref.getVariable() instanceof Local) {
                    for (Local taskLocal : allLocals) {
                        if (varref.getVariable().getName().equals(taskLocal.getName())) {
                            Output localOutput = LFF.createOutput();
                            localOutput.setName(varref.getVariable().getName());
                            var copyType = EcoreUtil.copy(taskLocal.getType());
                            localOutput.setType(copyType);

                            localOutput.setLocal("true");
                            
                            reactor.getOutputs().add(localOutput);
                            
                        }
                    }
                    
                }
            }
        }
        
        // set local inputs
        if (!(task.eContainer() instanceof BehaviorTree)) { // danach checken for (taskSources if(local) proceed
            var allLocals = new ArrayList<Local>();
            var seqOrFbOrPar = task.eContainer();
            while (!(seqOrFbOrPar instanceof BehaviorTree)) {
                if (seqOrFbOrPar instanceof Sequence) {
                    allLocals.addAll(((Sequence) seqOrFbOrPar).getLocals());
                } else if (seqOrFbOrPar instanceof Fallback){
                    allLocals.addAll(((Fallback) seqOrFbOrPar).getLocals());
                } else {
                    // WAS TUN FÜR PARs
                }
                seqOrFbOrPar = seqOrFbOrPar.eContainer();
            }
            
            for (VarRef varref : task.getTaskSources()) {
                if (varref.getVariable() instanceof Local) {
                    for (Local taskLocal : allLocals) {
                        if (varref.getVariable().getName().equals(taskLocal.getName())) {
                            Input localInput = LFF.createInput();
                            localInput.setName(varref.getVariable().getName());
                            var copyType = EcoreUtil.copy(taskLocal.getType());
                            localInput.setType(copyType);

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
