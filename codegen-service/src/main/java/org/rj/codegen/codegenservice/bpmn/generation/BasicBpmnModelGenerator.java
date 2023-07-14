package org.rj.codegen.codegenservice.bpmn.generation;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.builder.*;
import org.camunda.bpm.model.bpmn.instance.*;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.rj.codegen.codegenservice.bpmn.beans.ElementNode;
import org.rj.codegen.codegenservice.bpmn.beans.NodeData;
import org.rj.codegen.codegenservice.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.rj.codegen.codegenservice.bpmn.generation.BpmnConstants.*;

public class BasicBpmnModelGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(BasicBpmnModelGenerator.class);

    public BasicBpmnModelGenerator() {
    }

    public BpmnModelInstance generateModel(NodeData nodeData) {
        if (nodeData == null) throw new RuntimeException("Cannot generate model with null node data");

        LOG.info("Generating BPMN model data for graph: {}", Util.serializeOrThrow(nodeData));

        // Map of node IDs to node data
        final var nodesById = nodeData.getNodes().stream()
                .collect(Collectors.toMap(ElementNode::getId, Function.identity()));

        final var start = nodeData.getNodes().stream()
                .filter(node -> NodeTypes.START_EVENT.equalsIgnoreCase(node.getElementType()))
                .findFirst().orElseThrow(() -> new RuntimeException("No start event in node data"));

        // Iteratively build based on required connections; begin from a single start event
        final var nodes = new LinkedList<String>();
        final var builder = Bpmn.createExecutableProcess("process")
                .startEvent(start.getId())
                .name(start.getName())
                .done();

        int sequenceId = 0;
        nodes.add(start.getId());

        while (!nodes.isEmpty()) {
            final var nodeId = nodes.removeFirst();
            final var node = nodesById.get(nodeId);

            final ModelElementInstance nodeInstance = builder.getModelElementById(nodeId);
            if (nodeInstance == null) throw new RuntimeException("Cannot retrieve generated node: " + nodeId);

            if (!(nodeInstance instanceof FlowNode flowNode)) continue;

            final var targets = Optional.ofNullable(node.getConnectedTo()).orElseGet(List::of);
            if (targets.isEmpty()) continue;  // Nothing downstream of this node

            for (var target : targets) {
                var targetElement = nodesById.get(target.getTargetNode());
                if (targetElement == null)
                    throw new RuntimeException("Target element does not exist: " + target.getTargetNode());

                final var connectionId = "seq-" + (sequenceId++);
                final var outboundConnection = addOutboundConnection(flowNode.builder(), node, target, connectionId);

                final ModelElementInstance existingTarget = builder.getModelElementById(target.getTargetNode());
                if (existingTarget == null) {
                    addNode(outboundConnection, targetElement);
                    nodes.add(targetElement.getId());
                } else {
                    outboundConnection.connectTo(target.getTargetNode());
                }
            }
        }

        return builder;
    }

    private <B extends AbstractFlowNodeBuilder<B, E>, E extends FlowNode>
    void addNode(AbstractFlowNodeBuilder<B, E> builder, ElementNode element) {
        if (element == null) throw new RuntimeException("Cannot generate definition for null BPMN element");

        final var id = element.getId();
        final var name = element.getName();

        // Not differentiating between all element types for now
        final var type = element.getElementType();
        switch (type) {
            case NodeTypes.TASK_USER, NodeTypes.TASK_USER_TASK -> builder.userTask(id).name(name).done();
            case NodeTypes.TASK_SERVICE, NodeTypes.TASK_SERVICE_TASK -> builder.serviceTask(id).name(name).done();
            case NodeTypes.TASK_SCRIPT, NodeTypes.TASK_SCRIPT_TASK -> builder.scriptTask(id).name(name).done();
            case NodeTypes.TASK_MANUAL, NodeTypes.TASK_MANUAL_TASK -> builder.manualTask(id).name(name).done();
            case NodeTypes.TASK_BUSINESS_RULE, NodeTypes.TASK_BUSINESS_RULE_TASK -> builder.businessRuleTask(id).name(name).done();
            case NodeTypes.END_EVENT -> builder.endEvent(id).name(name).done();
            case NodeTypes.GATEWAY_EXCLUSIVE -> builder.exclusiveGateway(id).name(name).done();
            case NodeTypes.GATEWAY_INCLUSIVE -> builder.inclusiveGateway(id).name(name).done();

            default -> builder.manualTask(id).name(name).done();   // TODO
        }
    }

    private <B extends AbstractFlowNodeBuilder<B, E>, E extends FlowNode>
    AbstractFlowNodeBuilder<B, E> addOutboundConnection(AbstractFlowNodeBuilder<B, E> builder, ElementNode sourceElement,
                                                        ElementNode.Connection connection, String id) {
        if (builder == null || sourceElement == null || connection == null || StringUtils.isBlank(id))
            throw new RuntimeException("Cannot make connection with invalid null data");

        final var elementConnection = builder.sequenceFlowId(id);

        if (NodeTypes.GATEWAY_EXCLUSIVE.equals(sourceElement.getElementType())) {
            elementConnection.condition(connection.getDescription(), null);
        }

        return elementConnection;
    }

    public FlowNode addElement(ElementNode element, Process process) {
        if (element == null) throw new RuntimeException("Cannot generate definition for null BPMN element");

        // Not differentiating between all element types for now
        final var type = element.getElementType();
        final var generatedElement = switch (type)
        {
            case NodeTypes.START_EVENT -> createElement(process, element.getId(), StartEvent.class);
            case NodeTypes.END_EVENT -> createElement(process, element.getId(), EndEvent.class);
            default -> createElement(process, element.getId(), Task.class);
        };

        if (generatedElement == null) throw new RuntimeException(String.format("Failed to generate valid BPMN element for '%s'", element.getName()));

        return generatedElement;
    }

    private Pair<BpmnModelInstance, Process> createModel() {
        BpmnModelInstance modelInstance = Bpmn.createEmptyModel();

        final var definitions = modelInstance.newInstance(Definitions.class);
        definitions.setTargetNamespace("http://camunda.org/examples");
        modelInstance.setDefinitions(definitions);

        final var process = createElement(definitions, "process", Process.class);

        return Pair.of(modelInstance, process);
    }

    protected <T extends BpmnModelElementInstance> T createElement(BpmnModelElementInstance parentElement, String id, Class<T> elementClass) {
        T element = parentElement.getModelInstance().newInstance(elementClass);
        element.setAttributeValue("id", id, true);
        parentElement.addChildElement(element);
        return element;
    }

    public SequenceFlow createSequenceFlow(Process process, FlowNode from, FlowNode to) {
        String identifier = from.getId() + "-" + to.getId();
        SequenceFlow sequenceFlow = createElement(process, identifier, SequenceFlow.class);
        process.addChildElement(sequenceFlow);
        sequenceFlow.setSource(from);
        from.getOutgoing().add(sequenceFlow);
        sequenceFlow.setTarget(to);
        to.getIncoming().add(sequenceFlow);
        return sequenceFlow;
    }
}
