main:
# 	docker build -t host -f host.Dockerfile .
# 	docker pull opencord/quagga
	docker compose up -d
	sudo ovs-vsctl add-br br0
	sudo ovs-vsctl set bridge br0 protocols=OpenFlow14
	sudo ovs-vsctl set-controller br0 tcp:127.0.0.1:6653
	sudo ovs-docker add-port br0 eth1 R1 --ipaddress=172.20.0.2/16
	sudo ovs-docker add-port br0 eth1 R2 --ipaddress=172.20.0.3/16
	sudo ovs-docker add-port br0 eth1 R3 --ipaddress=172.20.0.4/16

clean:
	docker compose down
	sudo ovs-vsctl del-br br0