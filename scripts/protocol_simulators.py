"""Isolated Modbus/S7/MQTT network devices for the transport integration tests.

Install scripts/protocol-sim-requirements.txt into a dedicated virtual environment.
Run with --kind modbus|s7|mqtt; all listeners default to loopback, never a PLC address.
"""
import argparse
import asyncio
import ctypes
import json
import signal
import time


def modbus(args):
    from pymodbus.datastore import ModbusDeviceContext, ModbusServerContext, ModbusSequentialDataBlock
    from pymodbus.server import StartAsyncTcpServer
    device = ModbusDeviceContext(co=ModbusSequentialDataBlock(0, [False] * 256),
                                hr=ModbusSequentialDataBlock(0, [0] * 256))
    print(json.dumps({"ready": "modbus", "port": args.port}), flush=True)
    asyncio.run(StartAsyncTcpServer(ModbusServerContext(devices={1: device}, single=False),
                                   address=("127.0.0.1", args.port)))


def s7(args):
    from snap7.server import Server
    from snap7.type import SrvArea
    server = Server()
    db = (ctypes.c_uint8 * 256)()
    server.register_area(SrvArea.DB, 100, db)
    server.start(tcp_port=args.port)
    print(json.dumps({"ready": "s7", "port": args.port}), flush=True)
    try:
        while True:
            time.sleep(1)
    finally:
        server.stop()
        server.destroy()


def mqtt(args):
    import paho.mqtt.client as mqtt_client
    values = {"speed": 0, "enabled": False}
    client = mqtt_client.Client(mqtt_client.CallbackAPIVersion.VERSION2,
                                client_id="wcs-isolated-device", protocol=mqtt_client.MQTTv5)

    def connected(c, _userdata, _flags, reason, _properties):
        if reason.is_failure:
            raise RuntimeError(str(reason))
        c.subscribe("wcs-test/request", qos=1)
        # An unsolicited retained message must never satisfy the next correlated read.
        c.publish("wcs-test/response", json.dumps({"version": 1, "operation": "read",
                  "correlation_id": "stale", "direction": "response", "payload": {"speed": -999},
                  "timestamp": 1}), qos=1, retain=True)
        print(json.dumps({"ready": "mqtt", "port": args.port}), flush=True)

    def arrived(c, _userdata, message):
        body = json.loads(message.payload)
        if body["operation"] == "write":
            values.update(body["payload"])
        reply = {**body, "direction": "response", "timestamp": int(time.time() * 1000),
                 "payload": {key: values[key] for key in body["payload"]}}
        c.publish("wcs-test/response", json.dumps(reply), qos=1, retain=False)

    client.on_connect = connected
    client.on_message = arrived
    client.connect("127.0.0.1", args.port)
    client.loop_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--kind", choices=["modbus", "s7", "mqtt"], required=True)
    parser.add_argument("--port", type=int, required=True)
    arguments = parser.parse_args()
    globals()[arguments.kind](arguments)
