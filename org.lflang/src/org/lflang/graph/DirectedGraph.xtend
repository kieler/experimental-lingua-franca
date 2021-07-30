/* Instance of an action. */

/*************
Copyright (c) 2019, The University of California at Berkeley.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
***************/

package org.lflang.graph

import java.util.*
import java.util.stream.Collectors

import static extension org.lflang.util.CollectionUtil.plus
import org.lflang.util.CollectionUtil

/** 
 * Directed graph that maps nodes to its upstream and downstream neighbors. 
 * @author{Marten Lohstroh <marten@berkeley.edu>}
 */
class DirectedGraph<T> implements Graph<T> {

    // Note that while both those maps are mutable, the sets
    // they use as values may not be. They should only be
    // manipulated through CollectionUtil

    // If a node has no neighbors, it is still in the map with an empty set as a value.

    /** Adjacency map from vertices to their downstream immediate neighbors. */
    val Map<T, Set<T>> downstreamAdjacentNodes = new LinkedHashMap();
    
    /** Adjacency map from vertices to their upstream immediate neighbors. */
    val Map<T, Set<T>> upstreamAdjacentNodes = new LinkedHashMap();

    
    /**
     * Construct a new dependency graph.
     */
    new() {
        
    }
    
    /**
     * Mark the graph to have changed so that any cached analysis is refreshed
     * accordingly.
     */
    protected def graphChanged() {
        // To be overridden by subclasses that perform analysis.
    }
    
    /**
     * Return true if this graph has the given node in it.
     * @param node The node to look for.
     */
    override hasNode(T node) {
        nodes.contains(node)
    }
    
    /**
     * Return all immediate upstream neighbors of a given node.
     * @param node The node to report the immediate upstream neighbors of.
     */
    def Set<T> getUpstreamAdjacentNodes(T node) {
        Collections.unmodifiableSet(this.upstreamAdjacentNodes.getOrDefault(node, Set.of()))
    }

    /**
     * Return all immediate downstream neighbors of a given node.
     * @param node The node to report the immediate downstream neighbors of.
     */
    def Set<T> getDownstreamAdjacentNodes(T node) {
        Collections.unmodifiableSet(this.downstreamAdjacentNodes.getOrDefault(node, Set.of()))
    }

    
    /**
     * Add the given node to the graph.
     * @param node The node to add to the graph.
     */
    override void addNode(T node) {
        this.graphChanged()
        this.upstreamAdjacentNodes.putIfAbsent(node, Set.of())
        this.downstreamAdjacentNodes.putIfAbsent(node, Set.of())
    }
    
    /**
     * Remove the given node from the graph. This also eliminates any
     * edges from upstream and to downstream neighbors of this node.
     * @param The node to remove.
     */
    override removeNode(T node) {
        this.graphChanged()
        this.upstreamAdjacentNodes.remove(node)
        this.downstreamAdjacentNodes.remove(node)
        CollectionUtil.removeFromValues(this.upstreamAdjacentNodes, node);
        CollectionUtil.removeFromValues(this.downstreamAdjacentNodes, node);
    }
    
    /**
     * Add a new directed edge to the graph. The first argument is
     * the downstream node, the second argument the upstream node.
     * If either argument is null, do nothing.
     * @param sink The downstream immediate neighbor.
     * @param source The upstream immediate neighbor.
     */
    override addEdge(T sink, T source) {
        this.graphChanged()
        if (sink !== null && source !== null) {
            this.downstreamAdjacentNodes.compute(source, [k, set| set.plus(sink) ])
            this.upstreamAdjacentNodes.compute(sink, [k, set| set.plus(source) ])
        }
    }
    
    
    /**
     * Add new directed edges to the graph. The first argument is the
     * downstream node, the second argument a set of upstream nodes.
     * @param sink The downstream immediate neighbor.
     * @param sources The upstream immediate neighbors.
     */
    override addEdges(T sink, List<T> sources) {
        for (source : sources) {
            this.addEdge(sink, source)
        }
    }
    
    /**
     * Remove a directed edge from the graph.
     * @param sink The downstream immediate neighbor.
     * @param source The upstream immediate neighbor.
     */
    override removeEdge(T sink, T source) {
        this.graphChanged()
        this.upstreamAdjacentNodes.computeIfPresent(sink, [k, upstream| CollectionUtil.minus(upstream, source)])
        this.downstreamAdjacentNodes.computeIfPresent(source, [k, downstream| CollectionUtil.minus(downstream, sink)])
    }
    
    /**
     * Obtain a copy of this graph by creating an new instance and copying
     * the adjacency maps.
     */
    def copy() {
        val graph = new DirectedGraph<T>()
        for (entry : this.upstreamAdjacentNodes.entrySet) {
            graph.upstreamAdjacentNodes.put(entry.key, CollectionUtil.copy(entry.value))
        }
        for (entry : this.downstreamAdjacentNodes.entrySet) {
            graph.downstreamAdjacentNodes.put(entry.key, CollectionUtil.copy(entry.value))
        }
        return graph
    }
    
    /**
     * For a given a two adjacency maps, copy missing edges from the first
     * map to the second.
     * @param srcMap The adjacency map to copy edges from.
     * @param dstMap The adjacency map to copy edges to.
     */
    private def void mirror(Map<T, Set<T>> srcMap, Map<T, Set<T>> dstMap) {
        if (srcMap !== null && dstMap !== null) {
            for (entry : srcMap.entrySet) {
                val node = entry.getKey()
                val srcEdges = entry.getValue()
                dstMap.compute(node, [_node, dstEdges| {
                    // Node does not exist; add it.
                    if (dstEdges === null) return CollectionUtil.copy(srcEdges)

                    // Node does exist; add the missing edges.
                    var set = dstEdges
                    for (edge : srcEdges) {
                        set = set.plus(edge)
                    }
                    return set
                }])
            }
        }
    }
    
    /**
     * Merge another directed graph into this one.
     * @param another The graph to merge into this one.
     */
    def merge(DirectedGraph<T> another) {
        this.graphChanged()
        mirror(another.upstreamAdjacentNodes, this.upstreamAdjacentNodes)
        mirror(another.downstreamAdjacentNodes, this.downstreamAdjacentNodes)
    }
    
    /**
     * Return the set of nodes that have no neighbors listed in the given
     * adjacency map.
     */
    private def Set<T> independentNodes(Map<T, Set<T>> adjacencyMap) {
        var independent = new LinkedHashSet<T>()
        for (node : this.nodes) {
            val neighbors = adjacencyMap.get(node)
            if (neighbors === null || neighbors.size == 0) {
                independent.add(node)
            }
        }
        return independent
    }
    
    /**
     * Return the root nodes of this graph.
     * Root nodes have no upstream neighbors.
     */
    def rootNodes() {
        return independentNodes(this.upstreamAdjacentNodes)
    }
    
    /**
     * Return the leaf nodes of this graph.
     * Leaf nodes have no downstream neighbors.
     */
    def leafNodes() {
        return independentNodes(this.downstreamAdjacentNodes)
    }
    
    /**
     * Report the number of nodes in this graph.
     */
    override nodeCount() {
        this.nodes.size
    }

    /**
     * Report the number of directed edges in this graph.
     */    
    override edgeCount() {
       var edges = 0
       for(downstream : this.upstreamAdjacentNodes.keySet) {
           edges += this.upstreamAdjacentNodes.get(downstream).size
       }
       for(upstream : this.downstreamAdjacentNodes.keySet) {
           for (downstream : this.downstreamAdjacentNodes.get(upstream)) {
               if (this.upstreamAdjacentNodes.get(downstream) === null) {
                   edges++ // Account for possible asymmetry.
               }
           }
       }
       edges
    }
    
    /**
     * Return the nodes in this graph.
     */
    override Set<T> nodes() {
        return Collections.unmodifiableSet(this.downstreamAdjacentNodes.keySet)
    }
    
    def clear() {
        this.graphChanged()
        this.downstreamAdjacentNodes.clear()
        this.upstreamAdjacentNodes.clear()
    }
    
    /**
     * Return a textual list of the nodes.
     */
    override toString() {
        return nodes.stream().map([ it.toString() ]).collect(Collectors.joining(", ", "{", "}"));
    }
}
