package com.opentermx.serial;

import com.fazecast.jSerialComm.SerialPort;

import java.util.ArrayList;
import java.util.List;

public final class SerialPortDiscovery {

    private SerialPortDiscovery() {
    }

    public static List<SerialPortInfo> listAvailablePorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        List<SerialPortInfo> result = new ArrayList<>(ports.length);
        for (SerialPort p : ports) {
            result.add(new SerialPortInfo(
                    p.getSystemPortName(),
                    p.getDescriptivePortName(),
                    p.getPortDescription(),
                    p.getManufacturer()
            ));
        }
        return result;
    }
}