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
package nctu.winlab.ProxyArp;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.ARP;
import org.onlab.packet.Ip4Address;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;

import org.onosproject.net.ConnectPoint;
import org.onosproject.net.host.HostService;
import org.onosproject.net.edge.EdgePortService;

import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.DefaultTrafficSelector;

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

import java.util.Optional;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;


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
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EdgePortService edgePortService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    ApplicationId appId;

    private ProxyArpProcessor processor = new ProxyArpProcessor();

    HashMap<Ip4Address, MacAddress> arpTable = new HashMap<Ip4Address, MacAddress>();

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.ProxyArp");
        packetService.addProcessor(processor, PacketProcessor.director(2));
        requestPacketsIn();
        log.info("Started: " + appId.name());
    }

    @Deactivate
    protected void deactivate() {
        cancelPacketsIn();
        packetService.removeProcessor(processor);
        processor = null;
        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
       requestPacketsIn();
    }

    private void requestPacketsIn() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_ARP);
        packetService.requestPackets(selector.build(), PacketPriority.LOWEST, appId);
    }

    private void cancelPacketsIn() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_ARP);
        packetService.requestPackets(selector.build(), PacketPriority.LOWEST, appId);
    }

    private class ProxyArpProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }

            log.info("=== In ProxyArpProcessor ===");

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            MacAddress srcMac = ethPkt.getSourceMAC();

            ARP arpPkt = (ARP) ethPkt.getPayload();
            Ip4Address srcIp = Ip4Address.valueOf(arpPkt.getSenderProtocolAddress());
            Ip4Address dstIp = Ip4Address.valueOf(arpPkt.getTargetProtocolAddress());

            ConnectPoint srcCP = pkt.receivedFrom();

            /* 每當 packet-in 的時候就更新 arp table */
            arpTable.put(srcIp, srcMac);

            /* 判斷是 ARP Request 或是 ARP Reply */
            if (arpPkt.getOpCode() == ARP.OP_REQUEST) {
                MacAddress dstMac = arpTable.get(dstIp);

                if (dstMac == null) {       /* 如果找不到 dstIp的紀錄，就要 packet out to all edge ports */
                    log.info("TABLE MISS. Send request to edge ports");
                    for (ConnectPoint cp : edgePortService.getEdgePoints()) {
                        if (!cp.equals(srcCP)) {
                            TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder();
                            treatment.setOutput(cp.port());
                            
                            packetService.emit(new DefaultOutboundPacket(cp.deviceId(), treatment.build(), ByteBuffer.wrap(ethPkt.serialize())));
                            // log.info("Packet out to: " + cp);
                        }
                    }
                } else {                    /* generate ARP Reply */
                    Ethernet arpReply = ARP.buildArpReply(dstIp, dstMac, ethPkt);
                    TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder();
                    treatment.setOutput(srcCP.port());

                    packetService.emit(new DefaultOutboundPacket(srcCP.deviceId(), treatment.build(), ByteBuffer.wrap(arpReply.serialize())));

                    log.info("TABLE HIT. Requested MAC = " + dstMac);
                }
            } else if (arpPkt.getOpCode() == ARP.OP_REPLY) {
                log.info("RECV REPLY. Requested MAC = " + srcMac);
            }
        }
    }
}
