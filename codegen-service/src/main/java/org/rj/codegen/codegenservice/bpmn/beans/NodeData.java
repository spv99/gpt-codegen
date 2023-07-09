package org.rj.codegen.codegenservice.bpmn.beans;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.rj.codegen.codegenservice.util.Util;

import java.util.ArrayList;
import java.util.List;

public class NodeData {
    private List<ElementNode> nodes = new ArrayList<>();

    public NodeData() {
    }

    public List<ElementNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<ElementNode> nodes) {
        this.nodes = nodes;
    }

    @JsonIgnore
    public String serialize() {
        return Util.serializeOrThrow(this);
    }
}