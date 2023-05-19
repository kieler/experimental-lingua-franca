/*************
 * Copyright (c) 2023, Kiel University.
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

import static org.lflang.behaviortrees.TransformationUtil.connect;
import static org.lflang.behaviortrees.TransformationUtil.createRef;

import java.util.ArrayList;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.resource.IFragmentProvider.Fallback;
import org.lflang.lf.BehaviorTree;
import org.lflang.lf.LfFactory;
import org.lflang.lf.Output;
import org.lflang.lf.Reactor;
import org.lflang.lf.Sequence;

import com.google.common.collect.HashMultimap;

/**
 * Handles data port connections in behavior tree transformation.
 * 
 * @author Alexander Schulz-Rosengarten
 */
class DataConnector {

    static final LfFactory LFF = LfFactory.eINSTANCE;
    private final CodeGenerator codeGenerator;

    /**
     * @param codeGenerator
     */
    public DataConnector(CodeGenerator codeGenerator) {
        this.codeGenerator = codeGenerator;
    }

    /**
     * @return if any transformed reactor instantiates a Pre reactor.
     */
    public boolean usesPreReactor() {
        // TODO Auto-generated method stub
        return false;
    }
    
    /**
     * @return the Pre reactor defintion
     */
    public Reactor getPreReactorDefintion() {
        // TODO Auto-generated method stub
        return null;
    }
    
    /**
     * Initial analysis of the behavior tree to prepare for later transformation.
     * 
     * @param bt
     */
    public void analyze(BehaviorTree bt) {
        // TODO necessary?
    }

    /**
     * Connect root node to the public IO interface.
     * 
     * @param bt
     * @param reactor
     * @param child
     * @param instance
     */
    public void connectRootIO(BehaviorTree bt, Reactor reactor, TransformedNode child) {
        for (var input : child.inputs.keySet()) {
            reactor.getConnections().add(connect(reactor, null, input.getName(), child.reactor, child.instance, input.getName()));
        }
        for (var output : child.outputs.keySet()) {
            reactor.getConnections().add(connect(child.reactor, child.instance, output.getName(), reactor, null, output.getName()));
        }
    }

    /**
     * Create the data connections for this compound node and its children.
     * This includes forwarding IO (with adding ports to the reactors) 
     * and local connections (with merge reactions and PRE reactors).
     * 
     * @param node
     * @param children
     */
    public void connectData(TransformedNode node, ArrayList<TransformedNode> children) {
        // TODO Optimize loops!!!
        // TODO Also restructure!
        
        // Inputs
        for (var child : children) {
            for (var input : child.inputs.keySet()) {
                // If node has no port that mirrors the original port mirrored by the child
                if (!node.inputs.containsValue(child.inputs.get(input))) {
                    // Propagate port to parent for forwarding
                    var portCopy = EcoreUtil.copy(input);
                    node.reactor.getInputs().add(portCopy);
                    node.inputs.put(portCopy, child.inputs.get(input));
                }
                // Forward port
                node.reactor.getConnections().add(
                        connect(node.reactor, null, input.getName(), child.reactor, child.instance, input.getName()));
            }
        }
        
        // Outputs
        var writerMap = HashMultimap.<Output, TransformedNode>create();
        for (var child : children) {
            for (var output : child.outputs.keySet()) {
                // If node has no port that mirrors the original input port mirrored by the child
                if (!node.outputs.containsValue(child.outputs.get(output))) {
                    // Propagate port to parent for forwarding
                    var portCopy = EcoreUtil.copy(output);
                    node.reactor.getOutputs().add(portCopy);
                    node.outputs.put(portCopy, child.outputs.get(output));
                }
                // Register writer
                writerMap.put(child.outputs.get(output), child);
            }
        }
        for (var port : writerMap.keySet()) {
            var writers = writerMap.get(port);
            if (port.isPure() || writers.size() == 1) {
                // Normal connections
                for (var writer : writers) {
                    node.reactor.getConnections().add(
                            connect(writer.reactor, writer.instance, port.getName(), node.reactor, null, port.getName()));
                } 
            } else if (node.node instanceof Sequence || node.node instanceof Fallback) { // Can be merged sequentially
                // Merge
                var reaction = LFF.createReaction();
                node.reactor.getReactions().add(reaction);
                
                // Interface
                reaction.getEffects().add(createRef(node.reactor, null, port.getName()));
                var writerNames = new ArrayList<String>();
                for (var writer : writers) {
                    reaction.getTriggers().add(createRef(writer.reactor, writer.instance, port.getName()));
                    writerNames.add(writer.instance.getName());
                }
                
                var code = LFF.createCode();
                code.setBody(codeGenerator.getSequentialMerge(port.getName(), writerNames, null));
                reaction.setCode(code);
            } else {
                // Create normal connections. If there is only one writer, it will work. If there are multiple writers, it will cause a error in the compilation.
                for (var writer : writers) {
                    node.reactor.getConnections().add(
                            connect(writer.reactor, writer.instance, port.getName(), node.reactor, null, port.getName()));
                }
            }
        }
        
        
        // Locals
        // => Complicated
        
    }

}