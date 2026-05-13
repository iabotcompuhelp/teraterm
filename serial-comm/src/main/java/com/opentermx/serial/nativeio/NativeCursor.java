package com.opentermx.serial.nativeio;

import com.sun.jna.Structure;

import java.util.List;

/**
 * Mapeo JNA de {@code opentermx_cursor_t} (ver native/src/terminal_emu.h).
 */
@Structure.FieldOrder({"row", "col", "visible", "blink", "shape", "reserved"})
public class NativeCursor extends Structure {

    public int  row;
    public int  col;
    public byte visible;
    public byte blink;
    public byte shape;
    public byte reserved;

    public NativeCursor() {
        super();
    }

    public static class ByReference extends NativeCursor implements Structure.ByReference {}

    @Override
    protected List<String> getFieldOrder() {
        return List.of("row", "col", "visible", "blink", "shape", "reserved");
    }
}