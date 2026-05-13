package com.opentermx.serial.nativeio;

import com.sun.jna.Structure;

import java.util.List;

/**
 * Mapeo JNA de {@code opentermx_cell_t} (ver native/src/terminal_emu.h).
 */
@Structure.FieldOrder({"codepoint", "fgRgb", "bgRgb", "attrs", "dirty"})
public class NativeCell extends Structure {

    public int   codepoint;
    public int   fgRgb;
    public int   bgRgb;
    public short attrs;
    public short dirty;

    public NativeCell() {
        super();
    }

    public NativeCell(com.sun.jna.Pointer p) {
        super(p);
        read();
    }

    public static class ByReference extends NativeCell implements Structure.ByReference {}

    @Override
    protected List<String> getFieldOrder() {
        return List.of("codepoint", "fgRgb", "bgRgb", "attrs", "dirty");
    }
}