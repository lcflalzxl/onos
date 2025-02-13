/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onosproject.pipelines.fabric.impl.behaviour.pipeliner;

import com.google.common.collect.ImmutableList;
import org.onlab.packet.Ethernet;
import org.onlab.util.KryoNamespace;
import org.onlab.util.SharedExecutors;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.behaviour.NextGroup;
import org.onosproject.net.behaviour.Pipeliner;
import org.onosproject.net.behaviour.PipelinerContext;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleOperations;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criteria;
import org.onosproject.net.flow.criteria.PiCriterion;
import org.onosproject.net.flowobjective.FilteringObjective;
import org.onosproject.net.flowobjective.FlowObjectiveStore;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.IdNextTreatment;
import org.onosproject.net.flowobjective.NextObjective;
import org.onosproject.net.flowobjective.NextTreatment;
import org.onosproject.net.flowobjective.Objective;
import org.onosproject.net.flowobjective.ObjectiveError;
import org.onosproject.net.group.GroupDescription;
import org.onosproject.net.group.GroupService;
import org.onosproject.net.pi.runtime.PiAction;
import org.onosproject.net.pi.runtime.PiActionParam;
import org.onosproject.pipelines.fabric.FabricConstants;
import org.onosproject.pipelines.fabric.impl.FabricPipeconfLoader;
import org.onosproject.pipelines.fabric.impl.behaviour.AbstractFabricHandlerBehavior;
import org.onosproject.pipelines.fabric.impl.behaviour.FabricCapabilities;
import org.onosproject.store.serializers.KryoNamespaces;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.onosproject.net.flowobjective.NextObjective.Type.SIMPLE;
import static org.onosproject.pipelines.fabric.impl.behaviour.FabricInterpreter.ONE;
import static org.onosproject.pipelines.fabric.impl.behaviour.FabricInterpreter.ZERO;
import static org.onosproject.pipelines.fabric.impl.behaviour.FabricUtils.outputPort;
import static org.onosproject.pipelines.fabric.impl.behaviour.pipeliner.FilteringObjectiveTranslator.FWD_IPV4_ROUTING;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Pipeliner implementation for fabric pipeline which uses ObjectiveTranslator
 * implementations to translate flow objectives for the different blocks,
 * filtering, forwarding and next.
 */
public class FabricPipeliner extends AbstractFabricHandlerBehavior
        implements Pipeliner {

    private static final Logger log = getLogger(FabricPipeliner.class);
    private static final int DEFAULT_FLOW_PRIORITY = 100;
    public static final int DEFAULT_VLAN = 4094;

    protected static final KryoNamespace KRYO = new KryoNamespace.Builder()
            .register(KryoNamespaces.API)
            .register(FabricNextGroup.class)
            .build("FabricPipeliner");

    protected DeviceId deviceId;
    protected ApplicationId appId;
    protected FlowRuleService flowRuleService;
    protected GroupService groupService;
    protected FlowObjectiveStore flowObjectiveStore;
    protected CoreService coreService;

    private FilteringObjectiveTranslator filteringTranslator;
    private ForwardingObjectiveTranslator forwardingTranslator;
    private NextObjectiveTranslator nextTranslator;

    private final ExecutorService callbackExecutor = SharedExecutors.getPoolThreadExecutor();

    /**
     * Creates a new instance of this behavior with the given capabilities.
     *
     * @param capabilities capabilities
     */
    public FabricPipeliner(FabricCapabilities capabilities) {
        super(capabilities);
    }

    /**
     * Create a new instance of this behaviour. Used by the abstract projectable
     * model (i.e., {@link org.onosproject.net.Device#as(Class)}.
     */
    public FabricPipeliner() {
        super();
    }

    @Override
    public void init(DeviceId deviceId, PipelinerContext context) {
        this.deviceId = deviceId;
        this.flowRuleService = context.directory().get(FlowRuleService.class);
        this.groupService = context.directory().get(GroupService.class);
        this.flowObjectiveStore = context.directory().get(FlowObjectiveStore.class);
        this.filteringTranslator = new FilteringObjectiveTranslator(deviceId, capabilities);
        this.forwardingTranslator = new ForwardingObjectiveTranslator(deviceId, capabilities);
        this.nextTranslator = new NextObjectiveTranslator(deviceId, capabilities);
        this.coreService = context.directory().get(CoreService.class);
        this.appId = coreService.getAppId(FabricPipeconfLoader.PIPELINE_APP_NAME);

        initializePipeline();
    }

    protected void initializePipeline() {
        // Set up rules for packet-out forwarding. We support only IPv4 routing.
        final int cpuPort = capabilities.cpuPort().get();
        flowRuleService.applyFlowRules(
                ingressVlanRule(cpuPort, false, DEFAULT_VLAN),
                fwdClassifierRule(cpuPort, null, Ethernet.TYPE_IPV4, FWD_IPV4_ROUTING,
                        DEFAULT_FLOW_PRIORITY));
    }

    @Override
    public void filter(FilteringObjective obj) {
        final ObjectiveTranslation result = filteringTranslator.translate(obj);
        handleResult(obj, result);
    }

    @Override
    public void forward(ForwardingObjective obj) {
        final ObjectiveTranslation result = forwardingTranslator.translate(obj);
        handleResult(obj, result);
    }

    @Override
    public void next(NextObjective obj) {
        if (obj.op() == Objective.Operation.VERIFY) {
            // TODO: support VERIFY operation
            log.debug("VERIFY operation not yet supported for NextObjective, will return success");
            success(obj);
            return;
        }

        if (obj.op() == Objective.Operation.MODIFY && obj.type() != SIMPLE) {
            log.warn("MODIFY operation not yet supported for NextObjective {}, will return failure :(",
                    obj.type());
            if (log.isTraceEnabled()) {
                log.trace("Objective {}", obj);
            }
            fail(obj, ObjectiveError.UNSUPPORTED);
            return;
        }

        final ObjectiveTranslation result = nextTranslator.translate(obj);
        handleResult(obj, result);
    }

    @Override
    public List<String> getNextMappings(NextGroup nextGroup) {
        final FabricNextGroup fabricNextGroup = KRYO.deserialize(nextGroup.data());
        return fabricNextGroup.nextMappings().stream()
                .map(m -> format("%s -> %s", fabricNextGroup.type(), m))
                .collect(Collectors.toList());
    }

    private void handleResult(Objective obj, ObjectiveTranslation result) {
        if (result.error().isPresent()) {
            fail(obj, result.error().get());
            return;
        }
        processGroups(obj, result.groups());
        processFlows(obj, result.flowRules());
        if (obj instanceof NextObjective) {
            handleNextGroup((NextObjective) obj);
        }
        success(obj);
    }

    private void handleNextGroup(NextObjective obj) {
        switch (obj.op()) {
            case REMOVE:
                removeNextGroup(obj);
                break;
            case ADD:
            case ADD_TO_EXISTING:
            case REMOVE_FROM_EXISTING:
            case MODIFY:
                putNextGroup(obj);
                break;
            case VERIFY:
                break;
            default:
                log.error("Unknown NextObjective operation '{}'", obj.op());
        }
    }

    private void processFlows(Objective objective, Collection<FlowRule> flowRules) {
        if (flowRules.isEmpty()) {
            return;
        }

        if (log.isTraceEnabled()) {
            log.trace("Objective {} -> Flows {}", objective, flowRules);
        }

        final FlowRuleOperations.Builder ops = FlowRuleOperations.builder();
        switch (objective.op()) {
            case ADD:
            case ADD_TO_EXISTING:
            case MODIFY:
                flowRules.forEach(ops::add);
                break;
            case REMOVE:
            case REMOVE_FROM_EXISTING:
                flowRules.forEach(ops::remove);
                break;
            default:
                log.warn("Unsupported Objective operation {}", objective.op());
                return;
        }
        flowRuleService.apply(ops.build());
    }

    private void processGroups(Objective objective, Collection<GroupDescription> groups) {
        if (groups.isEmpty()) {
            return;
        }

        if (log.isTraceEnabled()) {
            log.trace("Objective {} -> Groups {}", objective, groups);
        }

        switch (objective.op()) {
            case ADD:
                groups.forEach(groupService::addGroup);
                break;
            case REMOVE:
                groups.forEach(group -> groupService.removeGroup(
                        deviceId, group.appCookie(), objective.appId()));
                break;
            case ADD_TO_EXISTING:
                groups.forEach(group -> groupService.addBucketsToGroup(
                        deviceId, group.appCookie(), group.buckets(),
                        group.appCookie(), group.appId())
                );
                break;
            case REMOVE_FROM_EXISTING:
                groups.forEach(group -> groupService.removeBucketsFromGroup(
                        deviceId, group.appCookie(), group.buckets(),
                        group.appCookie(), group.appId())
                );
                break;
            case MODIFY:
                // Modify is only supported for simple next objective
                // Replace group bucket directly
                groups.forEach(group -> groupService.setBucketsForGroup(
                        deviceId, group.appCookie(), group.buckets(),
                        group.appCookie(), group.appId())
                );
                break;
            default:
                log.warn("Unsupported Objective operation {}", objective.op());
        }
    }

    private void fail(Objective objective, ObjectiveError error) {
        CompletableFuture.runAsync(
                () -> objective.context().ifPresent(
                        ctx -> ctx.onError(objective, error)), callbackExecutor);

    }


    private void success(Objective objective) {
        CompletableFuture.runAsync(
                () -> objective.context().ifPresent(
                        ctx -> ctx.onSuccess(objective)), callbackExecutor);
    }

    private void removeNextGroup(NextObjective obj) {
        final NextGroup removed = flowObjectiveStore.removeNextGroup(obj.id());
        if (removed == null) {
            log.debug("NextGroup {} was not found in FlowObjectiveStore", obj);
        }
    }

    private void putNextGroup(NextObjective obj) {
        final List<String> nextMappings = obj.nextTreatments().stream()
                .map(this::nextTreatmentToMappingString)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        final FabricNextGroup nextGroup = new FabricNextGroup(obj.type(), nextMappings);
        flowObjectiveStore.putNextGroup(obj.id(), nextGroup);
    }

    private String nextTreatmentToMappingString(NextTreatment n) {
        switch (n.type()) {
            case TREATMENT:
                final PortNumber p = outputPort(n);
                return p == null ? "UNKNOWN"
                        : format("OUTPUT:%s", p.toString());
            case ID:
                final IdNextTreatment id = (IdNextTreatment) n;
                return format("NEXT_ID:%d", id.nextId());
            default:
                log.warn("Unknown NextTreatment type '{}'", n.type());
                return "???";
        }
    }

    public FlowRule ingressVlanRule(long port, boolean vlanValid, int vlanId) {
        final TrafficSelector selector = DefaultTrafficSelector.builder()
                .add(Criteria.matchInPort(PortNumber.portNumber(port)))
                .add(PiCriterion.builder()
                        .matchExact(FabricConstants.HDR_VLAN_IS_VALID, vlanValid ? ONE : ZERO)
                        .build())
                .build();
        final TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .piTableAction(PiAction.builder()
                        .withId(vlanValid ? FabricConstants.FABRIC_INGRESS_FILTERING_PERMIT
                                : FabricConstants.FABRIC_INGRESS_FILTERING_PERMIT_WITH_INTERNAL_VLAN)
                        .withParameter(new PiActionParam(FabricConstants.VLAN_ID, vlanId))
                        .build())
                .build();
        return DefaultFlowRule.builder()
                .withSelector(selector)
                .withTreatment(treatment)
                .forTable(FabricConstants.FABRIC_INGRESS_FILTERING_INGRESS_PORT_VLAN)
                .makePermanent()
                .withPriority(DEFAULT_FLOW_PRIORITY)
                .forDevice(deviceId)
                .fromApp(appId)
                .build();
    }

    public FlowRule fwdClassifierRule(int port, Short ethType, short ipEthType, byte fwdType, int priority) {
        final TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder()
                .matchInPort(PortNumber.portNumber(port))
                .matchPi(PiCriterion.builder()
                        .matchExact(FabricConstants.HDR_IP_ETH_TYPE, ipEthType)
                        .build());
        if (ethType != null) {
            selectorBuilder.matchEthType(ethType);
        }
        final TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .piTableAction(PiAction.builder()
                        .withId(FabricConstants.FABRIC_INGRESS_FILTERING_SET_FORWARDING_TYPE)
                        .withParameter(new PiActionParam(FabricConstants.FWD_TYPE, fwdType))
                        .build())
                .build();
        return DefaultFlowRule.builder()
                .withSelector(selectorBuilder.build())
                .withTreatment(treatment)
                .forTable(FabricConstants.FABRIC_INGRESS_FILTERING_FWD_CLASSIFIER)
                .makePermanent()
                .withPriority(priority)
                .forDevice(deviceId)
                .fromApp(appId)
                .build();
    }

    /**
     * NextGroup implementation.
     */
    private static class FabricNextGroup implements NextGroup {

        private final NextObjective.Type type;
        private final List<String> nextMappings;

        FabricNextGroup(NextObjective.Type type, List<String> nextMappings) {
            this.type = type;
            this.nextMappings = ImmutableList.copyOf(nextMappings);
        }

        NextObjective.Type type() {
            return type;
        }

        Collection<String> nextMappings() {
            return nextMappings;
        }

        @Override
        public byte[] data() {
            return KRYO.serialize(this);
        }
    }
}
