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
package org.lflang.diagram.synthesis.util;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.eclipse.elk.alg.layered.options.EdgeStraighteningStrategy;
import org.eclipse.elk.alg.layered.options.FixedAlignment;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy;
import org.eclipse.elk.alg.layered.options.OrderingStrategy;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.EdgeRouting;
import org.eclipse.elk.core.options.SizeConstraint;
import org.eclipse.xtext.xbase.lib.Extension;
import org.lflang.AttributeUtils;
import org.lflang.behaviortrees.BehaviorTrees;
import org.lflang.behaviortrees.BehaviorTrees.NodeType;
import org.lflang.diagram.synthesis.AbstractSynthesisExtensions;
import org.lflang.diagram.synthesis.LinguaFrancaSynthesis;
import org.lflang.diagram.synthesis.styles.LinguaFrancaStyleExtensions;
import org.lflang.generator.ReactorInstance;
import org.lflang.lf.Reactor;

import com.google.inject.Inject;

import de.cau.cs.kieler.klighd.SynthesisOption;
import de.cau.cs.kieler.klighd.kgraph.KEdge;
import de.cau.cs.kieler.klighd.kgraph.KNode;
import de.cau.cs.kieler.klighd.krendering.KContainerRendering;
import de.cau.cs.kieler.klighd.krendering.KPolyline;
import de.cau.cs.kieler.klighd.krendering.ViewSynthesisShared;
import de.cau.cs.kieler.klighd.krendering.extensions.KContainerRenderingExtensions;
import de.cau.cs.kieler.klighd.krendering.extensions.KEdgeExtensions;
import de.cau.cs.kieler.klighd.krendering.extensions.KLabelExtensions;
import de.cau.cs.kieler.klighd.krendering.extensions.KNodeExtensions;
import de.cau.cs.kieler.klighd.krendering.extensions.KPolylineExtensions;
import de.cau.cs.kieler.klighd.krendering.extensions.KRenderingExtensions;
import de.cau.cs.kieler.klighd.syntheses.DiagramSyntheses;

/**
 * Detect and convert (mimic) behavior trees in Lingua Franca diagrams.
 * 
 * @author{Alexander Schulz-Rosengarten <als@informatik.uni-kiel.de>}
 */
@ViewSynthesisShared
public class BehaviorTreeDiagrams extends AbstractSynthesisExtensions {
    
    // Related synthesis option
    public static final SynthesisOption BT_CATEGORY = 
            SynthesisOption.createCategory("Behavior Trees", false).setCategory(LinguaFrancaSynthesis.APPEARANCE);
    public static final SynthesisOption SHOW_BT = 
            SynthesisOption.createCheckOption("Tree Structure", true).setCategory(BT_CATEGORY);
    public static final Map<String, Direction> BT_DIRECTIONS = Map.of("Top-Down", Direction.DOWN, "Left-Right", Direction.RIGHT);
    public static final SynthesisOption BT_DIRECTION = 
            SynthesisOption.createChoiceOption("Layout Direction", new ArrayList<>(BT_DIRECTIONS.keySet()), "Top-Down").setCategory(BT_CATEGORY);
        
    @Inject @Extension private KNodeExtensions _kNodeExtensions;
    @Inject @Extension private KEdgeExtensions _kEdgeExtensions;
    @Inject @Extension private KLabelExtensions _kLabelExtensions;
    @Inject @Extension private KPolylineExtensions _kPolylineExtensions;
    @Inject @Extension private KRenderingExtensions _kRenderingExtensions;
    @Inject @Extension private KContainerRenderingExtensions _kContainerRenderingExtensions;
    @Inject @Extension private LinguaFrancaStyleExtensions _linguaFrancaStyleExtensions;
    @Inject @Extension private UtilityExtensions _utilityExtensions;
    
    

    public void handleBehaviorTrees(List<KNode> nodes, ReactorInstance reactor) {
        if (getBooleanValue(SHOW_BT)) {
            if (getBTNodeType(reactor) == NodeType.ROOT) { // If this is the top most BT node -> transform to tree
                // Create new nodes for reactors
                var btNodes = new LinkedHashMap<ReactorInstance, KNode>(); // New nodes
                var visit = new LinkedList<ReactorInstance>();
                visit.addAll(reactor.children);
                while(!visit.isEmpty()) {
                    var r = visit.pop();
                    var t = getBTNodeType(r);
                    if (t != null) {
                        if (t != NodeType.ROOT) {
                            btNodes.put(r, createBTNode(r, t));
                            visit.addAll(r.children);
                        } else {
                            visit.addAll(0, r.children);
                        }
                    }
                }
                
                // Connect nodes
                for(var entry : btNodes.entrySet()) {
                    var parent = entry.getValue();
                    for (var childReactor : entry.getKey().children) {
                        var child = btNodes.get(childReactor);
                        if (child == null && getBTNodeType(childReactor) == NodeType.ROOT && !childReactor.children.isEmpty()) {
                            child = btNodes.get(childReactor.children.get(0));
                        }
                        if (child != null) {
                            KEdge edge = _kEdgeExtensions.createEdge();
                            edge.setSource(parent);
                            edge.setTarget(child);
                            KPolyline line = _kEdgeExtensions.addPolyline(edge);
                            _linguaFrancaStyleExtensions.boldLineSelectionStyle(line);
                            _kPolylineExtensions.addHeadArrowDecorator(line);
                        }
                    }
                }
                
                // Container node
//                var container = _kNodeExtensions.createNode();
//                _kRenderingExtensions.addInvisibleContainerRendering(container);
//                container.getChildren().addAll(btNodes.values()); // Add new content
//                configureBehaviorTreeLayout(container, reactor.reactorDefinition);
                
                // Replace content
                nodes.clear();
//                nodes.add(container); 
                nodes.addAll(btNodes.values()); 
            }
        }
    }

    public void configureBehaviorTreeLayout(KNode node, Reactor reactor) {
        if (getBooleanValue(SHOW_BT) && getBTNodeType(reactor) == NodeType.ROOT) {
            // Layout
            DiagramSyntheses.setLayoutOption(node, CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID);
            DiagramSyntheses.setLayoutOption(node, CoreOptions.DIRECTION, BT_DIRECTIONS.getOrDefault(getObjectValue(BT_DIRECTION), Direction.DOWN));
            DiagramSyntheses.setLayoutOption(node, LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.BRANDES_KOEPF);
            DiagramSyntheses.setLayoutOption(node, LayeredOptions.NODE_PLACEMENT_BK_FIXED_ALIGNMENT, FixedAlignment.BALANCED);
            DiagramSyntheses.setLayoutOption(node, LayeredOptions.NODE_PLACEMENT_BK_EDGE_STRAIGHTENING, EdgeStraighteningStrategy.IMPROVE_STRAIGHTNESS);
            DiagramSyntheses.setLayoutOption(node, CoreOptions.EDGE_ROUTING, EdgeRouting.POLYLINE);
            DiagramSyntheses.setLayoutOption(node, LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY, OrderingStrategy.NODES_AND_EDGES);
//            DiagramSyntheses.setLayoutOption(node, CoreOptions.PADDING, new ElkPadding(0));
        }
    }
    
    private NodeType getBTNodeType(ReactorInstance reactor) {
        return getBTNodeType(reactor.reactorDefinition);
    }

    private NodeType getBTNodeType(Reactor reactor) {
        try {
            var type = AttributeUtils.findAttributeByName(reactor, BehaviorTrees.TYPE_ANNOTATION_NAME);
            var typeName = AttributeUtils.getFirstArgumentValue(type);
            if (typeName.startsWith("\"")) {
                typeName = typeName.substring(1, typeName.length() - 1);
            }
            return NodeType.valueOf(typeName.toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }

    private KNode createBTNode(ReactorInstance reactor, NodeType type) {
        var node = _kNodeExtensions.createNode();
        DiagramSyntheses.setLayoutOption(node, CoreOptions.NODE_SIZE_CONSTRAINTS, EnumSet.of(SizeConstraint.MINIMUM_SIZE, SizeConstraint.NODE_LABELS));
        _kNodeExtensions.setMinimalNodeSize(node, 25, 25);
        //associateWith(node, reactor.getDefinition().getReactorClass());
        //_utilityExtensions.setID(node, reactor.uniqueID());
        
        var reactorDecl = reactor.getDefinition().getReactorClass();
        var label = AttributeUtils.getLabel(reactorDecl);
        var name = reactorDecl.getName();
        var text = label != null && !label.isEmpty() ? label : name;
        
        KContainerRendering figure;
        switch(type) {
            case ACTION:
                figure = _kRenderingExtensions.addRectangle(node);
                addLabel(figure, text);
                break;
            case CONDITION:
                figure = _kRenderingExtensions.addEllipse(node);
                addLabel(figure, text);
                break;
            case FALLBACK:
                figure = _kRenderingExtensions.addRectangle(node);
                addLabel(figure, "?");
                break;
            case PARALLEL:
                figure = _kRenderingExtensions.addRectangle(node);
                addLabel(figure, "\u21c9");
                break;
            case SEQUENCE:
                figure = _kRenderingExtensions.addRectangle(node);
                addLabel(figure, "\u2192");
                break;
            default:
                return null;
        }
        
        if (figure != null) {
            _linguaFrancaStyleExtensions.boldLineSelectionStyle(figure);
        }
        
        return node;
    }
    
    private void addLabel(KContainerRendering figure, String text) {
        var ktext = _kContainerRenderingExtensions.addText(figure, text);
        _kRenderingExtensions.setFontSize(ktext, 8);
        _kRenderingExtensions.setSurroundingSpace(ktext, 5, 0);
    }

    /**
     * @param reactor
     * @return
     */
    public boolean isGenerated(Reactor reactor) {
        return getBTNodeType(reactor) != null && getBTNodeType(reactor) != NodeType.ROOT;
    }
}
