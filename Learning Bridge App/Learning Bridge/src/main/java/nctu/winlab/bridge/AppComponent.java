/*
 * Copyright 2022-present Open Networking Foundation
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
package nctu.winlab.bridge;

import java.util.HashMap;

import org.onlab.packet.MacAddress;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;

import org.onosproject.net.device.DeviceService;

import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.DefaultTrafficSelector;

import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.onlab.packet.Ethernet;

// import java.util.Dictionary;
// import java.util.Properties;

// import static org.onlab.util.Tools.get;

/**
 * Skeletal ONOS application component.
 */
@Component(
    immediate = true,
    service = AppComponent.class
)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    private ApplicationId appId;

    private LearningBridgeProcessor processor = new LearningBridgeProcessor();
    HashMap<DeviceId, HashMap<MacAddress, PortNumber>> devices = new HashMap<>();


    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.bridge");
        // cfgService.registerProperties(getClass());
        packetService.addProcessor(processor, PacketProcessor.director(2));

        /* 啟動時，如果已經存在 topology 的話 ，就先找出 controller 目前底下有哪些 device */
        log.info("Retrieving available devices...");
        for (Device d : deviceService.getAvailableDevices()) {
            devices.put(d.id(), new HashMap<MacAddress, PortNumber>());
            log.info("Found device: " + d.id());
        }

        /* Install rule (with very low priority) on each switch to request for packet-in */
        requestIntercepts();

        log.info("Started", appId.id());
    }

    @Deactivate
    protected void deactivate() {
        // cfgService.unregisterProperties(getClass(), false);
        withdrawIntercepts();
        packetService.removeProcessor(processor);
        processor = null;
        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        requestIntercepts();
    }

    /* Request packin-in through PacketService */
    private void requestIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private void withdrawIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    /**
     * Packet processor is responsible for recording in-coming packet's mac address and switch port.
     */
    private class LearningBridgeProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            // log.info("----------------In Process----------------");

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            DeviceId deviceId = pkt.receivedFrom().deviceId();
            MacAddress srcMac = ethPkt.getSourceMAC();
            MacAddress dstMac = ethPkt.getDestinationMAC();
            PortNumber inPort = pkt.receivedFrom().port();

            /* 封包進來，先判斷從哪個 switch 進來的 */
            if (devices.get(deviceId) == null) {
                devices.put(deviceId, new HashMap<MacAddress, PortNumber>());
                log.info("New Switch: " + deviceId);
            }

            if (devices.get(deviceId).get(srcMac) == null) {
                log.info("Add an entry to the port table of `" + deviceId + "`. MAC address: `" + srcMac
                 + "` => Port: `" + inPort + "`.");
            }
            devices.get(deviceId).put(srcMac, inPort);      // 紀錄(更新) src mac, in_port

            // log.info("Current record:");
            // devices.get(deviceId).forEach(
            //     (key, value)
            //         -> log.info(key + " = " + value));

            // if (dstMac == MacAddress.BROADCAST) {
            //     packetOut(context, PortNumber.ALL);
            //     return;
            // }

            PortNumber nextHop = devices.get(deviceId).get(dstMac);
            if (nextHop == null) {             /* 找不到 dst mac 的紀錄*/
                packetOut(context, PortNumber.FLOOD);
                log.info("MAC address `" + dstMac + "` is missed on `" + deviceId + "`. Flood the packet.");
            } else {
                packetOut(context, nextHop);
                installRule(context, nextHop);
                log.info("MAC address `" + dstMac + "` is matched on `" + deviceId + "`. Install a flow rule.");
            }
        }


        private void packetOut(PacketContext context, PortNumber portNumber) {
            context.treatmentBuilder().setOutput(portNumber);
            context.send();
            // log.info("---------Packet out--------- Port:" + portNumber);
        }


        private void installRule(PacketContext context, PortNumber portNumber) {
            Ethernet inPkt = context.inPacket().parsed();
            TrafficSelector.Builder selector = DefaultTrafficSelector.builder();

            selector.matchEthSrc(inPkt.getSourceMAC());
            selector.matchEthDst(inPkt.getDestinationMAC());

            TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder();
            treatment.setOutput(portNumber);

            ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                               .withSelector(selector.build())
                               .withTreatment(treatment.build())
                               .withPriority(30)
                               .withFlag(ForwardingObjective.Flag.VERSATILE)
                               .fromApp(appId)
                               .makeTemporary(30)
                               .add();

            flowObjectiveService.forward(context.inPacket().receivedFrom().deviceId(), forwardingObjective);
        }
    }
}
