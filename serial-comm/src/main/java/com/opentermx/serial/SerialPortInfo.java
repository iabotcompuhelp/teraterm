package com.opentermx.serial;

public record SerialPortInfo(
        String systemPortName,
        String descriptivePortName,
        String portDescription,
        String manufacturer
) {
}