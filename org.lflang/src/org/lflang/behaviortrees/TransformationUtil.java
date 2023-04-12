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

import java.util.stream.Stream;

import org.lflang.behaviortrees.BehaviorTrees.NodeType;
import org.lflang.lf.BehaviorTreeNode;
import org.lflang.lf.Connection;
import org.lflang.lf.Fallback;
import org.lflang.lf.Instantiation;
import org.lflang.lf.LfFactory;
import org.lflang.lf.Parallel;
import org.lflang.lf.Port;
import org.lflang.lf.Reactor;
import org.lflang.lf.Sequence;
import org.lflang.lf.SubTree;
import org.lflang.lf.Task;
import org.lflang.lf.VarRef;

/**
 * Utility methods for transforming behavior trees.
 * 
 * @author Alexander Schulz-Rosengarten
 */
class TransformationUtil {
    
    /* only static public interface */
    private TransformationUtil() {}
    
    static final LfFactory LFF = LfFactory.eINSTANCE;
    
    /**
     * Create a reference to a port of a reactor. The reactor is either the parent (instance == null) or 
     * an inner instantiation. Ports are extracted from the reactor via name matching.
     * 
     * @param reactor
     * @param instance
     * @param portName
     * @return
     */
    public static VarRef createRef(Reactor reactor, Instantiation instance, String portName) {
        var ref = LFF.createVarRef();
        var port = getPort(reactor, portName);

        if (port != null) {
            ref.setVariable(port);
            if (instance != null) {
                ref.setContainer(instance);
            }
            return ref;
        } else {
            return null;
        }
    }
    
    /**
     * Finds a port in a reactor by name.
     * 
     * @param reactor
     * @param portName
     * @return
     */
    public static Port getPort(Reactor reactor, String portName) {
        var opt = Stream.concat(reactor.getInputs().stream(), reactor.getOutputs().stream())
                .filter(p -> p.getName().equals(portName)).findFirst();
        if (opt.isPresent()) {
            return opt.get();
        } else {
            throw new IllegalArgumentException("Cannot find port with name " + portName + " in reactor " + reactor.getName());
        }
    }
    
    /**
     * Creates a connection. Both the left and reight side can be either the parent reactor (instance == null) or 
     * an inner instantiation.
     * 
     * @param leftR
     * @param leftI
     * @param leftPortName
     * @param rightR
     * @param rightI
     * @param rightPortName
     * @return
     */
    public static Connection connect(Reactor leftR, Instantiation leftI,
            String leftPortName, Reactor rightR, Instantiation rightI,
            String rightPortName) {
        var connection = LFF.createConnection();
        var leftVarRef = createRef(leftR, leftI, leftPortName);
        var rightVarRef = createRef(rightR, rightI, rightPortName);

        connection.getLeftPorts().add(leftVarRef);
        connection.getRightPorts().add(rightVarRef);

        return connection;
    }
    
    /**
     * Determines the NodeType based on a BehaviorTreeNode instance.
     * 
     * @param node
     * @return
     */
    public static NodeType getTypeOfNode(BehaviorTreeNode node) {
        if (node instanceof Sequence) {
            return NodeType.SEQUENCE;
        } else if (node instanceof Fallback) {
            return NodeType.FALLBACK;
        } else if (node instanceof Parallel) {
            return NodeType.PARALLEL;
        } else if (node instanceof Task) {
            return ((Task) node).isCondition() ? NodeType.CONDITION : NodeType.ACTION;
        } else if (node instanceof SubTree) {
            return NodeType.SUBTREE;
        }
        return null;
    }
}
