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
package nctu.winlab.unicastdhcp;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.IPv4;
import org.onlab.packet.UDP;
import org.onlab.packet.TpPort;
import org.onlab.packet.DHCP;

import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;

import org.onosproject.net.ConnectPoint;
import org.onosproject.net.FilteredConnectPoint;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;

import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficTreatment;

import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.net.intent.IntentService;
// import org.onosproject.net.intent.Key;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private final DhcpConfigListener dhcpListener = new DhcpConfigListener();
    private final ConfigFactory<ApplicationId, DhcpConfig> factory = new ConfigFactory<ApplicationId, DhcpConfig>(
        APP_SUBJECT_FACTORY, DhcpConfig.class, "UnicastDhcpConfig") {
        @Override
        public DhcpConfig createConfig() {
            return new DhcpConfig();
        }
    };

    ApplicationId appId;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    // @Reference(cardinality = ReferenceCardinality.MANDATORY)
    // protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected IntentService intentService;

    private DhcpPacketProcessor dhcpProcessor = new DhcpPacketProcessor();

    private HashMap<MacAddress, ConnectPoint> hosts = new HashMap<>();

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.unicastdhcp");
        cfgService.addListener(dhcpListener);
        cfgService.registerConfigFactory(factory);
        packetService.addProcessor(dhcpProcessor, PacketProcessor.director(1));
        requestPackets();
        log.info("Started: " + appId.name());
    }

    @Deactivate
    protected void deactivate() {
        cfgService.removeListener(dhcpListener);
        cfgService.unregisterConfigFactory(factory);
        packetService.removeProcessor(dhcpProcessor);
        cancelPackets();
        log.info("Stopped");
    }

    private class DhcpConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
                    && event.configClass().equals(DhcpConfig.class)) {
                DhcpConfig config = cfgService.getConfig(appId, DhcpConfig.class);
                ConnectPoint connectPoint = config.connectPoint();
                if (config != null) {
                    log.info("DHCP server is connected to `" + connectPoint.deviceId() + "`, port `"
                     + connectPoint.port() + "`");
                }
            }
        }
    }

    private void requestPackets() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
                .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT));
        packetService.requestPackets(selector.build(), PacketPriority.CONTROL, appId);
    }

    private void cancelPackets() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
                .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT));
        packetService.cancelPackets(selector.build(), PacketPriority.CONTROL, appId);
    }


    private class DhcpPacketProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            if (context.isHandled() || context.inPacket().parsed().getEtherType() == Ethernet.TYPE_ARP) {
                return;
            }

            log.info("=== In DhcpPacketProcessor ===");
            // log.info("dhcpServer: " + dhcpServer.deviceId() + ", port: " + dhcpServer.port() + ", "
            //     + intentService.getIntentCount());

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            MacAddress srcMac = ethPkt.getSourceMAC();
            MacAddress dstMac = ethPkt.getDestinationMAC();

            ConnectPoint srcCP = pkt.receivedFrom();
            ConnectPoint dhcpServerCP = cfgService.getConfig(appId, DhcpConfig.class).connectPoint();

            IPv4 ipv4Packet = (IPv4) ethPkt.getPayload();
            UDP udpPacket = (UDP) ipv4Packet.getPayload();
            DHCP dhcpPacket = (DHCP) udpPacket.getPayload();

            log.info("DHCP packet type: " + dhcpPacket.getPacketType());

            log.info("[Create Intent] Host -> DHCP_Server");
            createIntent(context, srcCP, dhcpServerCP, true);

            log.info("[Create Intent] DHCP_Server -> Host");
            createIntent(context, dhcpServerCP, srcCP, false);
        }

        private void createIntent(PacketContext context, ConnectPoint ingress, ConnectPoint egress, boolean toServer) {
            TrafficSelector.Builder selector;
            if (toServer) {                                            /* If Host -> dhcp server, then match src mac */
                selector = DefaultTrafficSelector.builder()
                        .matchEthSrc(context.inPacket().parsed().getSourceMAC())
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPProtocol(IPv4.PROTOCOL_UDP)
                        .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
                        .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT));
            } else {                                                   /* If dhcp_server -> Host, then match dst mac */
                selector = DefaultTrafficSelector.builder()
                        .matchEthDst(context.inPacket().parsed().getSourceMAC())
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPProtocol(IPv4.PROTOCOL_UDP)
                        .matchUdpSrc(TpPort.tpPort(UDP.DHCP_SERVER_PORT))
                        .matchUdpDst(TpPort.tpPort(UDP.DHCP_CLIENT_PORT));
            }

            TrafficTreatment treatment = DefaultTrafficTreatment.emptyTreatment();

            FilteredConnectPoint ingressPoint = new FilteredConnectPoint(ingress);
            FilteredConnectPoint egressPoint = new FilteredConnectPoint(egress);

            log.info("Intent `" + ingress.deviceId() + "`, port `" + ingress.port() +
                     "` => `" + egress.deviceId() + "`, port `" + egress.port() + "` is submitted.");

            PointToPointIntent intent = PointToPointIntent.builder()
                        .filteredIngressPoint(ingressPoint)
                        .filteredEgressPoint(egressPoint)
                        .selector(selector.build())
                        .treatment(treatment)
                        .priority(50000)
                        .appId(appId)
                        .build();
            intentService.submit(intent);
            // log.info("-----------");
        }
    }
}
            // TrafficSelector ingressSelector = DefaultTrafficSelector.builder()
            //             .matchEthSrc(context.inPacket().parsed().getSourceMAC())
            //             .matchEthType(Ethernet.TYPE_IPV4)
            //             .matchIPProtocol(IPv4.PROTOCOL_UDP)
            //             .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
            //             .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT))
            //             .build();

            // TrafficSelector egressSelector = DefaultTrafficSelector.builder()
            //             .matchEthType(Ethernet.TYPE_IPV4)
            //             .matchIPProtocol(IPv4.PROTOCOL_UDP)
            //             .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
            //             .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT))
            //             .build();