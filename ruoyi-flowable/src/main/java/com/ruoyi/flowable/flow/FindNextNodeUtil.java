package com.ruoyi.flowable.flow;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.Expression;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.*;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Tony
 * @date 2021/4/19 20:51
 */
public class FindNextNodeUtil {

    /**
     * 获取下一步骤的用户任务
     *
     * @param repositoryService 服务
     * @param task              当前节点任务
     * @return {@link List<UserTask> List<UserTask>}
     */
    public static List<UserTask> getNextUserTasks(RepositoryService repositoryService, org.flowable.task.api.Task task) {
        List<UserTask> data = new ArrayList<>();
        // 查询流程定义
        ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(task.getProcessDefinitionId())
                .singleResult();
        // 获取流程定义的BPMN模型
        BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinition.getId());

        // 找到与当前任务相连的 SequenceFlow
        List<SequenceFlow> outgoingFlows = bpmnModel.getMainProcess()
                .findFlowElementsOfType(SequenceFlow.class)
                .stream()
                .filter(sequenceFlow -> sequenceFlow.getSourceRef().equals(task.getTaskDefinitionKey()))
                .collect(Collectors.toList());

        // 获取下一个节点的 FlowElement 数据
        if (!outgoingFlows.isEmpty()) {
            SequenceFlow sequenceFlow = outgoingFlows.get(0); // 假设只有一个出线
            FlowElement nextFlowElement = bpmnModel.getMainProcess()
                    .getFlowElement(sequenceFlow.getTargetRef());
            if (nextFlowElement instanceof UserTask) {
                data.add((UserTask) nextFlowElement);
            }
        }

        return data;
    }

    /**
     * 获取下一步骤的用户任务
     *
     * @param repositoryService
     * @param map
     * @return
     */
    public static List<UserTask> getNextUserTasks(RepositoryService repositoryService, org.flowable.task.api.Task task, Map<String, Object> map) {
        List<UserTask> data = new ArrayList<>();
        ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().processDefinitionId(task.getProcessDefinitionId()).singleResult();
        BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinition.getId());
        Process mainProcess = bpmnModel.getMainProcess();
        Collection<FlowElement> flowElements = mainProcess.getFlowElements();
        String key = task.getTaskDefinitionKey();
        FlowElement flowElement = bpmnModel.getFlowElement(key);
        next(flowElements, flowElement, map, data);
        return data;
    }

    /**
     * 启动流程时获取下一步骤的用户任务
     *
     * @param repositoryService
     * @param map
     * @return
     */
    public static List<UserTask> getNextUserTasksByStart(RepositoryService repositoryService, ProcessDefinition processDefinition, Map<String, Object> map) {
        List<UserTask> data = new ArrayList<>();
        BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinition.getId());
        Process mainProcess = bpmnModel.getMainProcess();
        Collection<FlowElement> flowElements = mainProcess.getFlowElements();
        String key = null;
        // 找到开始节点 并获取唯一key
        for (FlowElement flowElement : flowElements) {
            if (flowElement instanceof StartEvent) {
                key = flowElement.getId();
                break;
            }
        }
        FlowElement flowElement = bpmnModel.getFlowElement(key);
        next(flowElements, flowElement, map, data);
        return data;
    }


    /**
     * 查找下一节点
     *
     * @param flowElements
     * @param flowElement
     * @param map
     * @param nextUser
     */
    public static void next(Collection<FlowElement> flowElements, FlowElement flowElement, Map<String, Object> map, List<UserTask> nextUser) {
        //如果是结束节点
        if (flowElement instanceof EndEvent) {
            //如果是子任务的结束节点
            if (getSubProcess(flowElements, flowElement) != null) {
                flowElement = getSubProcess(flowElements, flowElement);
            }
        }
        //获取Task的出线信息--可以拥有多个
        List<SequenceFlow> outGoingFlows = null;
        if (flowElement instanceof Task) {
            outGoingFlows = ((Task) flowElement).getOutgoingFlows();
        } else if (flowElement instanceof Gateway) {
            outGoingFlows = ((Gateway) flowElement).getOutgoingFlows();
        } else if (flowElement instanceof StartEvent) {
            outGoingFlows = ((StartEvent) flowElement).getOutgoingFlows();
        } else if (flowElement instanceof SubProcess) {
            outGoingFlows = ((SubProcess) flowElement).getOutgoingFlows();
        } else if (flowElement instanceof CallActivity) {
            outGoingFlows = ((CallActivity) flowElement).getOutgoingFlows();
        }
        if (outGoingFlows != null && outGoingFlows.size() > 0) {
            //遍历所有的出线--找到可以正确执行的那一条
            for (SequenceFlow sequenceFlow : outGoingFlows) {
                //1.有表达式，且为true
                //2.无表达式
                String expression = sequenceFlow.getConditionExpression();
                if (expression == null ||
                        expressionResult(map, expression.substring(expression.lastIndexOf("{") + 1, expression.lastIndexOf("}")))) {
                    //出线的下一节点
                    String nextFlowElementID = sequenceFlow.getTargetRef();
                    if (checkSubProcess(nextFlowElementID, flowElements, nextUser)) {
                        continue;
                    }

                    //查询下一节点的信息
                    FlowElement nextFlowElement = getFlowElementById(nextFlowElementID, flowElements);
                    //调用流程
                    if (nextFlowElement instanceof CallActivity) {
                        CallActivity ca = (CallActivity) nextFlowElement;
                        if (ca.getLoopCharacteristics() != null) {
                            UserTask userTask = new UserTask();
                            userTask.setId(ca.getId());

                            userTask.setId(ca.getId());
                            userTask.setLoopCharacteristics(ca.getLoopCharacteristics());
                            userTask.setName(ca.getName());
                            nextUser.add(userTask);
                        }
                        next(flowElements, nextFlowElement, map, nextUser);
                    }
                    //用户任务
                    if (nextFlowElement instanceof UserTask) {
                        nextUser.add((UserTask) nextFlowElement);
                    }
                    //排他网关
                    else if (nextFlowElement instanceof ExclusiveGateway) {
                        next(flowElements, nextFlowElement, map, nextUser);
                    }
                    //并行网关
                    else if (nextFlowElement instanceof ParallelGateway) {
                        next(flowElements, nextFlowElement, map, nextUser);
                    }
                    //接收任务
                    else if (nextFlowElement instanceof ReceiveTask) {
                        next(flowElements, nextFlowElement, map, nextUser);
                    }
                    //服务任务
                    else if (nextFlowElement instanceof ServiceTask) {
                        next(flowElements, nextFlowElement, map, nextUser);
                    }
                    //子任务的起点
                    else if (nextFlowElement instanceof StartEvent) {
                        next(flowElements, nextFlowElement, map, nextUser);
                    }
                    //结束节点
                    else if (nextFlowElement instanceof EndEvent) {
                        next(flowElements, nextFlowElement, map, nextUser);
                    }
                }
            }
        }
    }

    /**
     * 判断是否是多实例子流程并且需要设置集合类型变量
     */
    public static boolean checkSubProcess(String Id, Collection<FlowElement> flowElements, List<UserTask> nextUser) {
        for (FlowElement flowElement1 : flowElements) {
            if (flowElement1 instanceof SubProcess && flowElement1.getId().equals(Id)) {

                SubProcess sp = (SubProcess) flowElement1;
                if (sp.getLoopCharacteristics() != null) {
                    String inputDataItem = sp.getLoopCharacteristics().getInputDataItem();
                    UserTask userTask = new UserTask();
                    userTask.setId(sp.getId());
                    userTask.setLoopCharacteristics(sp.getLoopCharacteristics());
                    userTask.setName(sp.getName());
                    nextUser.add(userTask);
                    return true;
                }
            }
        }

        return false;

    }

    /**
     * 查询一个节点的是否子任务中的节点，如果是，返回子任务
     *
     * @param flowElements 全流程的节点集合
     * @param flowElement  当前节点
     * @return
     */
    public static FlowElement getSubProcess(Collection<FlowElement> flowElements, FlowElement flowElement) {
        for (FlowElement flowElement1 : flowElements) {
            if (flowElement1 instanceof SubProcess) {
                for (FlowElement flowElement2 : ((SubProcess) flowElement1).getFlowElements()) {
                    if (flowElement.equals(flowElement2)) {
                        return flowElement1;
                    }
                }
            }
        }
        return null;
    }


    /**
     * 根据ID查询流程节点对象, 如果是子任务，则返回子任务的开始节点
     *
     * @param Id           节点ID
     * @param flowElements 流程节点集合
     * @return
     */
    public static FlowElement getFlowElementById(String Id, Collection<FlowElement> flowElements) {
        for (FlowElement flowElement : flowElements) {
            if (flowElement.getId().equals(Id)) {
                //如果是子任务，则查询出子任务的开始节点
                if (flowElement instanceof SubProcess) {
                    return getStartFlowElement(((SubProcess) flowElement).getFlowElements());
                }
                return flowElement;
            }
            if (flowElement instanceof SubProcess) {
                FlowElement flowElement1 = getFlowElementById(Id, ((SubProcess) flowElement).getFlowElements());
                if (flowElement1 != null) {
                    return flowElement1;
                }
            }
        }
        return null;
    }

    /**
     * 返回流程的开始节点
     *
     * @param flowElements 节点集合
     * @description:
     */
    public static FlowElement getStartFlowElement(Collection<FlowElement> flowElements) {
        for (FlowElement flowElement : flowElements) {
            if (flowElement instanceof StartEvent) {
                return flowElement;
            }
        }
        return null;
    }

    /**
     * 校验el表达式
     * 注意：字符串整数9位，小数8位都能够正确转换，基本够用。如果要求精度更高。请转换成BigDecimal处理，
     * 对应的公式数字比较也要换成BigDecimal进行大小比较
     * 例如：123456789.12345678
     *
     * @param map        上下文数据
     * @param expression 判断表达式
     * @return
     */
    public static boolean expressionResult(Map<String, Object> map, String expression) {
        Expression exp = AviatorEvaluator.compile(expression);
        // 处理参数，数字字符串转换为数字，同时进行参数检验，缺少参数抛出异常
        exp.getVariableFullNames().forEach(s -> {
            if (!map.containsKey(s)) {
                throw new IllegalArgumentException("跳转表达是计算缺少" + s + "参数");
            }
            Object o = map.get(s);
            if (o instanceof String) {
                String value = ((String) o).trim();
                // 使用正则表达式检查是否为数字
                if (value.matches("-?\\d+(\\.\\d+)?")) {
                    // 字符串是数字，转换为 double
                    double number = Double.parseDouble(value);
                    map.put(s, number);
                }
            }
        });
        final Object execute = exp.execute(map);
        return Boolean.parseBoolean(String.valueOf(execute));
    }
}
