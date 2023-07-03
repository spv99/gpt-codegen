package org.rj.codegen.codegenservice.bpmn.generation;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.builder.*;
import org.camunda.bpm.model.bpmn.builder.ProcessBuilder;
import org.camunda.bpm.model.bpmn.instance.*;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.rj.codegen.codegenservice.bpmn.beans.ConnectionNode;
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

    public BasicBpmnModelGenerator() { }

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
                if (targetElement == null) throw new RuntimeException("Target element does not exist: " + target.getTargetNode());

                final ModelElementInstance existingTarget = builder.getModelElementById(target.getTargetNode());
                if (existingTarget == null) {
                    addNode(flowNode.builder().sequenceFlowId("seq-" + (sequenceId++)), targetElement);
                    nodes.add(targetElement.getId());
                }
                else {
                    flowNode.builder().sequenceFlowId("seq-" + (sequenceId++)).connectTo(target.getTargetNode());
                }
            }
        }

        return builder;
    }

//    public BpmnModelInstance prevGenerateModel(NodeData nodeData) {
//        if (nodeData == null) throw new RuntimeException("Cannot generate model with null node data");
//
//        LOG.info("Generating BPMN model data for graph: {}", Util.serializeOrThrow(nodeData));
//
//        final var elementsById = nodeData.getNodes().stream()
//                .collect(Collectors.toMap(ElementNode::getId, Function.identity()));
//
//        // Map of node -> connected nodes
//        final Map<String, List<String>> connected = new HashMap<>();
//        for (final var conn : nodeData.getConnections()) {
//            connected.computeIfAbsent(conn.getSource(), __ -> new ArrayList<>())
//                    .add(conn.getDest());
//        }
//
//        final var start = nodeData.getNodes().stream()
//                .filter(node -> NodeTypes.START_EVENT.equals(node.getType()))
//                .findFirst().orElseThrow(() -> new RuntimeException("No start event in node data"));
//
//
//        // Iteratively build based on required connections; begin from a single start event
//        final var nodes = new LinkedList<String>();
//        final var builder = Bpmn.createExecutableProcess("process")
//                .startEvent(start.getId())
//                .name(start.getName())
//                .done();
//int tmp=0;
//        nodes.add(start.getId());
//        while (!nodes.isEmpty()) {
//            final var node = nodes.removeFirst();
//
//            final ModelElementInstance nodeInstance = builder.getModelElementById(node);
//            if (nodeInstance == null) throw new RuntimeException("Cannot retrieve generated node: " + node);
//
//            if (!(nodeInstance instanceof FlowNode flowNode)) continue;
//
//            final var targets = connected.get(node);
//            if (targets == null) continue;  // Nothing downstream of this node
//
//            for (var target : targets) {
//                var targetElement = elementsById.get(target);
//                if (targetElement == null) throw new RuntimeException("Target element does not exist: " + target);
//
////                if (NodeTypes.SEQUENCE_FLOW.equals(targetElement.getType())) {
////                    final var resolved_targets = connected.get(targetElement.getId());
////                    if (resolved_targets == null || resolved_targets.isEmpty()) throw new RuntimeException("Sequence flow has no destinations nodes");
////
////                    target = resolved_targets.get(0);
////                    targetElement = elementsByName.get(target);
////                }
//
//                final ModelElementInstance existingTarget = builder.getModelElementById(target);
//                if (existingTarget == null) {
//                    addNode(flowNode.builder().sequenceFlowId("seq-" + (tmp++)), targetElement);
//                    nodes.add(targetElement.getId());
//                }
//                else {
//                    flowNode.builder().sequenceFlowId("seq-" + (tmp++)).connectTo(target);
//                }
//            }
//        }
//
//        return builder;
//    }

    private <B extends AbstractFlowNodeBuilder<B, E>, E extends FlowNode>
    void addNode(AbstractFlowNodeBuilder<B, E> builder, ElementNode element) {
        if (element == null) throw new RuntimeException("Cannot generate definition for null BPMN element");

        final var id = element.getId();
        final var name = element.getName();

        // Not differentiating between all element types for now
        final var type = element.getElementType();
        switch (type)
        {
            case NodeTypes.TASK_USER, NodeTypes.TASK_USER_TASK -> builder.userTask(id).name(name).done();
            case NodeTypes.TASK_SERVICE, NodeTypes.TASK_SERVICE_TASK -> builder.serviceTask(id).name(name).done();
            case NodeTypes.TASK_SCRIPT, NodeTypes.TASK_SCRIPT_TASK -> builder.scriptTask(id).name(name).done();
            case NodeTypes.TASK_MANUAL, NodeTypes.TASK_MANUAL_TASK -> builder.manualTask(id).name(name).done();
            case NodeTypes.END_EVENT -> builder.endEvent(id).name(name).done();
            case NodeTypes.GATEWAY_EXCLUSIVE -> builder.exclusiveGateway(id).name(name).done();
            //case NodeTypes.SEQUENCE_FLOW -> {}

            default -> builder.manualTask(id).name(name).done();   // TODO
        }
    }

//    public List<String> validateNodeData(NodeData nodeData) {
//        if (nodeData == null) return List.of("No data was returned");
//
//        // TODO: Skip this check for now until the model schema is more rigorously defined
//        if (1 < 2) return List.of();
//
//        // Make sure all nodes have AT MOST one outbound connection, UNLESS they are a gateway
//        final var nodesById = nodeData.getNodes().stream().collect(Collectors.toMap(ElementNode::getId, Function.identity()));
//        final var nodeOutboundConnection = new HashMap<String, String>();
//
//        for (final var conn : nodeData.getConnections()) {
//            final var src = conn.getSource();
//            if (!nodeOutboundConnection.containsKey(src)) {
//                // First connection seen from this node, so simply record it
//                nodeOutboundConnection.put(src, conn.getDest());
//                continue;
//            }
//
//            // There is already a connection from this node. Report an error UNLESS this is a gateway
//            final var node = nodesById.get(src);
//            if (node == null) {
//                return List.of(String.format("There is a connection from '%s' to '%s', but no node exists with ID '%s'", src, conn.getDest(), conn.getDest()));
//            }
//
//            if (!NodeTypes.GATEWAY_EXCLUSIVE.equalsIgnoreCase(node.getElementType())) {
//                return List.of(String.format("Node '%s' has more than one outbound connection which is not allowed since it is not a gateway node. " +
//                        "Add a gateway node if you need to make a branching decision, as stated in the requirements", src));
//            }
//        }
//
//        return List.of();
//    }
//
//    public BpmnModelInstance generateModelOld(NodeData nodeData) {
//        if (nodeData == null) throw new RuntimeException("Cannot generate model with null node data");
//
//        final var modelWithProcess = createModel();
//        final var model = modelWithProcess.getLeft();
//        final var process = modelWithProcess.getRight();
//
//        Map<String, FlowNode> generatedElements = new HashMap<>();
//
//        for (final var element : nodeData.getNodes()) {
//            final var generatedNode = addElement(element, process);
//            generatedElements.put(element.getName(), generatedNode);
//        }
//
//        for (final var connection : nodeData.getConnections()) {
//            final var source = generatedElements.get(connection.getSource());
//            final var dest = generatedElements.get(connection.getDest());
//
//            createSequenceFlow(process, source, dest);
//        }
//
//        return model;
//    }

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

    public static void main(String[] args) {
        test3();
    }
//
//    private static void test1() {
//        final var nodeData = new NodeData();
//
//        nodeData.getNodes().add(new ElementNode("start", "Start", "startEvent"));
//        nodeData.getNodes().add(new ElementNode("end", "End", "endEvent"));
//
//        nodeData.getConnections().add(new ConnectionNode("start", "end", ""));
//
//        BasicBpmnModelGenerator generator = new BasicBpmnModelGenerator();
//        final var model = generator.generateModel(nodeData);
//
//
//
//        final var serialized = Bpmn.convertToString(model);
//        System.out.println(serialized);
//    }

    private static void test2() {
        final var response = "{\n" +
                "    \"elements\": [\n" +
                "        {\"name\":\"start\",\"type\":\"startEvent\",\"x\":0,\"y\":100},\n" +
                "        {\"name\":\"request_approval\",\"type\":\"userTask\",\"x\":200,\"y\":75},\n" +
                "        {\"name\":\"end\",\"type\":\"endEvent\",\"x\":400,\"y\":100},\n" +
                "        {\"name\":\"service\",\"type\":\"serviceTask\",\"x\":400,\"y\":50}\n" +
                "    ],\n" +
                "    \"connections\": [\n" +
                "        {\"source\":\"start\",\"dest\":\"request_approval\",\"comment\":\"\"},\n" +
                "        {\"source\":\"request_approval\",\"dest\":\"end\",\"comment\":\"approve\"},\n" +
                "        {\"source\":\"request_approval\",\"dest\":\"service\",\"comment\":\"approve\"},\n" +
                "        {\"source\":\"request_approval\",\"dest\":\"end\",\"comment\":\"reject\"}\n" +
                "    ]\n" +
                "}";

        final var generator = new BasicBpmnModelGenerator();
        final var model = generator.generateModel(Util.deserializeOrThrow(response, NodeData.class, RuntimeException::new));

        System.out.println(Bpmn.convertToString(model));
    }

    private static void test3() {
        final var response = "{\"elements\":[{\"name\":\"start\",\"type\":\"startEvent\",\"x\":0,\"y\":100},{\"name\":\"userTask\",\"type\":\"userTask\",\"x\":200,\"y\":75},{\"name\":\"decision\",\"type\":\"exclusiveGateway\",\"x\":400,\"y\":50},{\"name\":\"approved\",\"type\":\"sequenceFlow\",\"x\":480,\"y\":150},{\"name\":\"notApproved\",\"type\":\"sequenceFlow\",\"x\":480,\"y\":50},{\"name\":\"end\",\"type\":\"endEvent\",\"x\":600,\"y\":100}],\"connections\":[{\"source\":\"start\",\"dest\":\"userTask\",\"comment\":\"\"},{\"source\":\"userTask\",\"dest\":\"decision\",\"comment\":\"\"},{\"source\":\"decision\",\"dest\":\"approved\",\"comment\":\"user approves\"},{\"source\":\"decision\",\"dest\":\"notApproved\",\"comment\":\"user does not approve\"},{\"source\":\"approved\",\"dest\":\"end\",\"comment\":\"\"},{\"source\":\"notApproved\",\"dest\":\"end\",\"comment\":\"\"}]}";
        final var generator = new BasicBpmnModelGenerator();
        final var model = generator.generateModel(Util.deserializeOrThrow(response, NodeData.class, RuntimeException::new));

        System.out.println(Bpmn.convertToString(model));
    }

}
