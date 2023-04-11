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

/**
 * BehaviorTree constants.
 * 
 * @author Alexander Schulz-Rosengarten
 */
public class BehaviorTrees {

    // BT node types
    public enum NodeType {
        ROOT, ACTION, CONDITION, SEQUENCE, FALLBACK, PARALLEL, SUBTREE;
        
        public String getRactorName() {
            switch (this) {
                case ACTION: return "Action";
                case CONDITION: return "Condition";
                case FALLBACK: return "Fallback";
                case PARALLEL: return "Parallel";
                case SEQUENCE: return "Sequence";
                default: return null;
            }
        }
    }
    
    // Interface port names
    public static String START = "start";
    public static String SUCCESS = "success";
    public static String FAILURE = "failure";
    public static String RUNNING = "running";
    
    // Annotation name
    public static String TYPE_ANNOTATION_NAME = "btnode";
    
    
}
