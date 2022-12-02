/*************
* Copyright (c) 2022, Kiel University.
*
* Redistribution and use in source and binary forms, with or without modification,
* are permitted provided that the following conditions are met:
*
* 1. Redistributions of source code must retain the above copyright notice,
*    this list of conditions and the following disclaimer.
*
* 2. Redistributions in binary form must reproduce the above copyright notice,
*    this list of conditions and the following disclaimer in the documentation
*    and/or other materials provided with the distribution.
*
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
package org.lflang.behaviortrees;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.lflang.lf.BehaviorTree;
import org.lflang.lf.BehaviorTreeNode;
import org.lflang.lf.Input;
import org.lflang.lf.Instantiation;
import org.lflang.lf.LfFactory;
import org.lflang.lf.Model;
import org.lflang.lf.Reactor;
import org.lflang.lf.Sequence;
import org.lflang.lf.Task;
import org.lflang.lf.Type;
import org.lflang.lf.impl.InputImpl;
import org.lflang.lf.impl.TypeImpl;

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
        ROOT,
        ACTION, CONDITION,
        SEQUENCE, FALLBACK, PARALLEL
    }
    
    public static void transform(Model lfModel) {
        new BehaviorTreeTransformation().transformAll(lfModel);
    }
    public static Reactor transformVirtual(BehaviorTree bt) {
        return new BehaviorTreeTransformation().transformBTree(bt, new ArrayList<Reactor>());
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
        var instantiations = Lists.newArrayList(Iterators.filter(lfModel.eAllContents(), Instantiation.class));
        for (var i : instantiations) {
            if (transformed.containsKey(i.getReactorClass())) {
                i.setReactorClass(transformed.get(i.getReactorClass()));
            }
        }
        // Remove BTrees
        lfModel.getBtrees().clear();
        // Add new reactors to model
        lfModel.getReactors().addAll(newReactors);
    }

    private Reactor transformBTree(BehaviorTree bt, List<Reactor> newReactors) {
        /*
         * lieber hier den Input start und
         * die Outputs success, failure erstellen oder bei Transform Task?
         */
        var reactor = LFF.createReactor();
        newReactors.add(reactor);
        reactor.setName(bt.getName());
        addBTNodeAnnotation(reactor, NodeType.ROOT.toString());
        
        var nodeReactor = transformNode(bt.getRootNode(), newReactors);
        var instance = LFF.createInstantiation();
        instance.setReactorClass(nodeReactor);
        instance.setName("root");
        reactor.getInstantiations().add(instance);
        
        return reactor;
    }
    
    private Reactor transformNode(BehaviorTreeNode node, List<Reactor> newReactors) {
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
        reactor.setName("Node"+nodeNameCounter++);
        addBTNodeAnnotation(reactor, NodeType.SEQUENCE.toString());
        
        for (var node : seq.getNodes()) {
            var nodeReactor = transformNode(node, newReactors);
            var instance = LFF.createInstantiation();
            instance.setReactorClass(nodeReactor);
            instance.setName("node" + seq.getNodes().indexOf(node));
            reactor.getInstantiations().add(instance);
        }
        
        return reactor;
    }

    private Reactor transformTask(Task task, List<Reactor> newReactors) {
        var reactor = LFF.createReactor();
        newReactors.add(reactor);
        reactor.setName("Node"+nodeNameCounter++);
        Input startInput = LFF.createInput();
        startInput.setName("start");
        Type startType = LFF.createType(); // keinen neuen Type erstellen sondern Bool nehmen(vordefiniert prolly)
        startType.setId("bool");
        startInput.setType(startType);
        reactor.getInputs().add(startInput);
        addBTNodeAnnotation(reactor, NodeType.ACTION.toString());
        
        if (task.getReaction() != null) {
            var copyReaction = EcoreUtil.copy(task.getReaction());
//            copyReaction.getTriggers().clear();
//            var varrefimpl = LFF.createVarRef().set;
//            copyReaction.getTriggers().add(start);
            reactor.getReactions().add(copyReaction);
        }
        
        return reactor;
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
}



