/*************
Copyright (c) 2020, The University of California at Berkeley.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND 
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED 
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE 
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES 
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; 
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON 
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT 
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS 
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
***************/

package org.lflang.scoping;

import static java.util.Collections.emptyList;
import static org.lflang.ASTUtils.*;

import com.google.inject.Inject;

import java.util.ArrayList;
import java.util.Optional;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.xtext.naming.SimpleNameProvider;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.Scopes;
import org.eclipse.xtext.scoping.impl.SelectableBasedScope;
import org.lflang.behaviortrees.BehaviorTreeTransformation;
import org.lflang.lf.Assignment;
import org.lflang.lf.BehaviorTree;
import org.lflang.lf.Connection;
import org.lflang.lf.Deadline;
import org.lflang.lf.Fallback;
import org.lflang.lf.Import;
import org.lflang.lf.ImportedReactor;
import org.lflang.lf.Input;
import org.lflang.lf.Instantiation;
import org.lflang.lf.Model;
import org.lflang.lf.Parallel;
import org.lflang.lf.Reaction;
import org.lflang.lf.Reactor;
import org.lflang.lf.ReactorDecl;
import org.lflang.lf.Sequence;
import org.lflang.lf.SubTree;
import org.lflang.lf.SubTreeBinding;
import org.lflang.lf.Task;
import org.lflang.lf.VarRef;
import org.lflang.lf.LfPackage;
import org.lflang.lf.Mode;

/**
 * This class enforces custom rules. In particular, it resolves references to
 * parameters, ports, actions, and timers. Ports can be referenced across at
 * most one level of hierarchy. Parameters, actions, and timers can be
 * referenced locally, within the reactor.
 *
 * @author Marten Lohstroh
 * @see <a href="https://www.eclipse.org/Xtext/documentation/303_runtime_concepts.html#scoping"></a>
 */
public class LFScopeProviderImpl extends AbstractLFScopeProvider {

    @Inject
    private SimpleNameProvider nameProvider;

    @Inject
    private LFGlobalScopeProvider scopeProvider;

    /**
     * Enumerate of the kinds of references.
     */
    enum RefType {
        NULL,
        TRIGGER,
        SOURCE,
        EFFECT,
        DEADLINE,
        CLEFT,
        CRIGHT
    }

    /**
     * Depending on the provided context, construct the appropriate scope
     * for the given reference.
     *
     * @param context   The AST node in which a to-be-resolved reference occurs.
     * @param reference The reference to resolve.
     */
    @Override
    public IScope getScope(EObject context, EReference reference) {
        if (context instanceof VarRef) {
            return getScopeForVarRef((VarRef) context, reference);
        } else if (context instanceof Assignment) {
            return getScopeForAssignment((Assignment) context, reference);
        } else if (context instanceof Instantiation) {
            return getScopeForReactorDecl(context, reference);
        } else if (context instanceof Reactor) {
            return getScopeForReactorDecl(context, reference);
        } else if (context instanceof ImportedReactor) {
            return getScopeForImportedReactor((ImportedReactor) context, reference);
        } else if (context instanceof BehaviorTree) { 
            return getScopeForReactorDecl(context, reference);
        } else if (context instanceof SubTreeBinding) { 
            return getScopeForSubTreeBinding((SubTreeBinding)context, reference);
        }
        return super.getScope(context, reference);
    }

    /**
     * Filter out candidates that do not originate from the file listed in
     * this particular import statement.
     */
    protected IScope getScopeForImportedReactor(ImportedReactor context, EReference reference) {
        String importURI = ((Import) context.eContainer()).getImportURI();
        var importedURI = scopeProvider.resolve(importURI == null ? "" : importURI, context.eResource());
        if (importedURI != null) {
            var uniqueImportURIs = scopeProvider.getImportedUris(context.eResource());
            var descriptions = scopeProvider.getResourceDescriptions(context.eResource(), uniqueImportURIs);
            var description = descriptions.getResourceDescription(importedURI);
            return SelectableBasedScope.createScope(IScope.NULLSCOPE, description, null, reference.getEReferenceType(), false);
        }
        return Scopes.scopeFor(emptyList());
    }

    /**
     * @param obj       Instantiation or Reactor that has a ReactorDecl to resolve.
     * @param reference The reference to link to a ReactorDecl node.
     */
    protected IScope getScopeForReactorDecl(EObject obj, EReference reference) {

        // Find the local Model
        Model model = null;
        EObject container = obj;
        while(model == null && container != null) {
            container = container.eContainer();
            if (container instanceof Model) {
                model = (Model)container;
            }
        }
        if (model == null) {
            return Scopes.scopeFor(emptyList());
        }

        // Collect eligible candidates, all of which are local (i.e., not in other files).
        var locals = new ArrayList<ReactorDecl>(model.getReactors());
        locals.addAll(model.getBtrees());

        // Either point to the import statement (if it is renamed)
        // or directly to the reactor definition.
        for (Import it : model.getImports()) {
            for (ImportedReactor ir : it.getReactorClasses()) {
                if (ir.getName() != null) {
                    locals.add(ir);
                } else if (ir.getReactorClass() != null) {
                    locals.add(ir.getReactorClass());
                }
            }
        }
        return Scopes.scopeFor(locals);
    }

    protected IScope getScopeForAssignment(Assignment assignment, EReference reference) {

        if (reference == LfPackage.Literals.ASSIGNMENT__LHS) {
            var target = ((Instantiation) assignment.eContainer()).getReactorClass();
            if (target instanceof BehaviorTree bt) {
                return Scopes.scopeFor(bt.getParameters());
            } else {
                var defn = toDefinition(target);
                if (defn != null) {
                    return Scopes.scopeFor(allParameters(defn));
                }
            }
        }
        if (reference == LfPackage.Literals.ASSIGNMENT__RHS) {
            return Scopes.scopeFor(((Reactor) assignment.eContainer().eContainer()).getParameters());
        }
        return Scopes.scopeFor(emptyList());
    }

    protected IScope getScopeForVarRef(VarRef variable, EReference reference) {
        if (reference == LfPackage.Literals.VAR_REF__VARIABLE) {
            // Resolve hierarchical reference Problem: container bei TaskImpl leer
            Reactor reactor = null;
            Mode mode = null;
            BehaviorTree behtree = null;
            if (variable.eContainer().eContainer() instanceof Reactor) {
                reactor = (Reactor) variable.eContainer().eContainer();
            } else if (variable.eContainer().eContainer() instanceof Mode) {
                mode = (Mode) variable.eContainer().eContainer();
                reactor = (Reactor) variable.eContainer().eContainer().eContainer();
            } else if (variable.eContainer() instanceof Task || variable.eContainer() instanceof Sequence ||
                    variable.eContainer() instanceof Fallback || variable.eContainer() instanceof Parallel) {
                var parent = variable.eContainer();
                while (behtree == null) {
                    if (parent.eContainer() instanceof BehaviorTree) {
                        behtree = (BehaviorTree) parent.eContainer();
                    } else {
                        parent = parent.eContainer();
                    }
                }
                
            }
            else {
                return Scopes.scopeFor(emptyList());
            }

            RefType type = getRefType(variable);

            if (variable.getContainer() != null) { // Resolve hierarchical port reference hier nur für varref aus instantiations!
                var instanceName = nameProvider.getFullyQualifiedName(variable.getContainer());
                var instances = new ArrayList<Instantiation>(reactor.getInstantiations());
                if (mode != null) {
                    instances.addAll(mode.getInstantiations());
                }

                if (instanceName != null) {
                    for (var instance : instances) {
                        if (instance.getReactorClass() instanceof BehaviorTree && instance.getName().equals(instanceName.toString())) {
                            var bt = (BehaviorTree) instance.getReactorClass();
                            // FIXME This causes a side-effect on the model in the scoper, check if this can be improved (e.g. moved to parsing)
                            BehaviorTreeTransformation.addImplictInterface(bt);
                            switch (type) {
                                case TRIGGER:
//                                    TODO
                                case SOURCE:
//                                    TODO return Scopes.scopeFor(bt.getInputs();
                                case CLEFT:
                                    return Scopes.scopeFor(bt.getOutputs());
                                case EFFECT:
                                    return Scopes.scopeFor(bt.getInputs());
                                case DEADLINE:
                                case CRIGHT:
                                    return Scopes.scopeFor(bt.getInputs());
                            }
                        } else {
                            var defn = toDefinition(instance.getReactorClass());
                            if (defn != null && instance.getName().equals(instanceName.toString())) {
                                switch (type) {
                                case TRIGGER:
                                case SOURCE:
                                case CLEFT:
                                    return Scopes.scopeFor(allOutputs(defn));
                                case EFFECT:
                                case DEADLINE:
                                case CRIGHT:
                                    return Scopes.scopeFor(allInputs(defn));
                                }
                            }
                        }
                    }
                }
                return Scopes.scopeFor(emptyList());
            } else if (reactor != null){
                // Resolve local reference
                switch (type) {
                case TRIGGER: {
                    var candidates = new ArrayList<EObject>();
                    if (mode != null) {
                        candidates.addAll(mode.getActions());
                        candidates.addAll(mode.getTimers());
                    }
                    candidates.addAll(allInputs(reactor));
                    candidates.addAll(allActions(reactor));
                    candidates.addAll(allTimers(reactor));
                    return Scopes.scopeFor(candidates);
                }
                case SOURCE: {//TODO
//                    return super.getScope(variable, reference);
//                    return Scopes.scopeFor(allInputs(reactor));
                    var candidates = new ArrayList<EObject>();
                    if (mode != null) {
                        candidates.addAll(mode.getActions());
                        candidates.addAll(mode.getTimers());
                    }
                    candidates.addAll(allInputs(reactor));
                    candidates.addAll(allActions(reactor));
                    candidates.addAll(allTimers(reactor));
                    return Scopes.scopeFor(candidates);
                }    
                case EFFECT: {
                    var candidates = new ArrayList<EObject>();
                    if (mode != null) {
                        candidates.addAll(mode.getActions());
                        candidates.addAll(reactor.getModes());
                    }
                    candidates.addAll(allOutputs(reactor));
                    candidates.addAll(allActions(reactor));
                    return Scopes.scopeFor(candidates);
                }
                case DEADLINE:
                case CLEFT:
                    return Scopes.scopeFor(allInputs(reactor));
                case CRIGHT:
                    return Scopes.scopeFor(allOutputs(reactor));
                default:
                    return Scopes.scopeFor(emptyList());
                }
            } else if (behtree != null) {
                switch (type) {
                    case SOURCE: {//TODO mb ineffizient?
//                        return super.getScope(variable, reference);
//                        return Scopes.scopeFor(allInputs(reactor));
                        var candidates = new ArrayList<EObject>();
                        var seqOrFb = variable.eContainer().eContainer();
                        while (!(seqOrFb instanceof BehaviorTree)) {
                            if (seqOrFb instanceof Sequence) {
                                candidates.addAll(((Sequence) seqOrFb).getLocals());
                            } else if (seqOrFb instanceof Fallback){ // TODO change for PAR?
                                candidates.addAll(((Fallback) seqOrFb).getLocals());
                            } else {
                                candidates.addAll(((Parallel) seqOrFb).getLocals());

                            }
                            seqOrFb = seqOrFb.eContainer();
                        }
                        
                        candidates.addAll(behtree.getInputs());
//                        candidates.addAll(allActions(reactor));
//                        candidates.addAll(allTimers(reactor));
                        return Scopes.scopeFor(candidates);
                    }    
                    case EFFECT: {
                        var candidates = new ArrayList<EObject>();
                        var seqOrFb = variable.eContainer().eContainer();
                        while (!(seqOrFb instanceof BehaviorTree)) {
                            if (seqOrFb instanceof Sequence) {
                                candidates.addAll(((Sequence) seqOrFb).getLocals());
                            } else if (seqOrFb instanceof Fallback){
                                candidates.addAll(((Fallback) seqOrFb).getLocals());
                            } else {
                                candidates.addAll(((Parallel) seqOrFb).getLocals());
                            }
                            
                            seqOrFb = seqOrFb.eContainer();
                        }
                        candidates.addAll(behtree.getOutputs());
//                        candidates.addAll(allActions(reactor));
                        return Scopes.scopeFor(candidates);
                    }
                    default:
                        return Scopes.scopeFor(emptyList());
                    }
            } else {
                return Scopes.scopeFor(emptyList());
            }
        } else { // Resolve instance
            return super.getScope(variable, reference);
        }
    }

    private RefType getRefType(VarRef variable) {
        if (variable.eContainer() instanceof Deadline) {
            return RefType.DEADLINE;
        } else if (variable.eContainer() instanceof Reaction) {
            var reaction = (Reaction) variable.eContainer();
            if (reaction.getTriggers().contains(variable)) {
                return RefType.TRIGGER;
            } else if (reaction.getSources().contains(variable)) {
                return RefType.SOURCE;
            } else if (reaction.getEffects().contains(variable)) {
                return RefType.EFFECT;
            }
        } else if (variable.eContainer() instanceof Connection) {
            var conn = (Connection) variable.eContainer();
            if (conn.getLeftPorts().contains(variable)) {
                return RefType.CLEFT;
            } else if (conn.getRightPorts().contains(variable)) {
                return RefType.CRIGHT;
            }
        } else if (variable.eContainer() instanceof Task) {
            var task = (Task) variable.eContainer();
            if (task.getSources().contains(variable)) {
                return RefType.SOURCE;
            } else if (task.getEffects().contains(variable)) {
                return RefType.EFFECT;
            }
        }
        return RefType.NULL;
    }
    
    protected IScope getScopeForSubTreeBinding(SubTreeBinding binding, EReference reference) {
        var parent = binding.eContainer();
        BehaviorTree btree = null;
        while (btree == null) {
            if (parent.eContainer() instanceof BehaviorTree) {
                btree = (BehaviorTree) parent.eContainer();
            } else {
                parent = parent.eContainer();
            }
        }
        var subTrees = new ArrayList<SubTree>();
        var bTreeContents = btree.eAllContents();
        while(bTreeContents.hasNext()) {
            var obj = bTreeContents.next();
            if (obj instanceof SubTree) {
                subTrees.add((SubTree)obj);
            }
        }
        if (reference == LfPackage.Literals.SUB_TREE_BINDING__SOURCE_CONTAINER 
                || reference == LfPackage.Literals.SUB_TREE_BINDING__TARGET_CONTAINER ) {
            return Scopes.scopeFor(subTrees);
        } else if (reference == LfPackage.Literals.SUB_TREE_BINDING__SOURCE) {
            var instanceName = nameProvider.getFullyQualifiedName(binding.getSourceContainer());
            if (instanceName != null) {
                Optional<SubTree> subTree = subTrees.stream().filter(it -> instanceName.toString().equals(it.getName())).findFirst();
                if (subTree.isPresent()) {
                    return Scopes.scopeFor(subTree.get().getBehaviorTree().getOutputs());
                }
            }
            var candidates = new ArrayList<EObject>();
            var seqOrFb = binding.eContainer().eContainer();
            while (!(seqOrFb instanceof BehaviorTree)) {
                if (seqOrFb instanceof Sequence) {
                    candidates.addAll(((Sequence) seqOrFb).getLocals());
                } else if (seqOrFb instanceof Fallback){ // TODO change for PAR?
                    candidates.addAll(((Fallback) seqOrFb).getLocals());
                } else if (seqOrFb instanceof Parallel){
                    candidates.addAll(((Parallel) seqOrFb).getLocals());
                }
                seqOrFb = seqOrFb.eContainer();
            }
            candidates.addAll(btree.getInputs());
            return Scopes.scopeFor(candidates);
        }else if (reference == LfPackage.Literals.SUB_TREE_BINDING__TARGET) {
            var instanceName = nameProvider.getFullyQualifiedName(binding.getTargetContainer());
            if (instanceName != null) {
                Optional<SubTree> subTree = subTrees.stream().filter(it -> instanceName.toString().equals(it.getName())).findFirst();
                if (subTree.isPresent()) {
                    return Scopes.scopeFor(subTree.get().getBehaviorTree().getInputs());
                }
            }
            var candidates = new ArrayList<EObject>();
            var seqOrFb = binding.eContainer().eContainer();
            while (!(seqOrFb instanceof BehaviorTree)) {
                if (seqOrFb instanceof Sequence) {
                    candidates.addAll(((Sequence) seqOrFb).getLocals());
                } else if (seqOrFb instanceof Fallback){ // TODO change for PAR?
                    candidates.addAll(((Fallback) seqOrFb).getLocals());
                } else if (seqOrFb instanceof Parallel){
                    candidates.addAll(((Parallel) seqOrFb).getLocals());
                }
                seqOrFb = seqOrFb.eContainer();
            }
            candidates.addAll(btree.getOutputs());
            return Scopes.scopeFor(candidates);
        }
        return Scopes.scopeFor(emptyList());
    }
}
