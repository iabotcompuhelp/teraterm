package com.opentermx.macro;

import java.util.List;

public record MacroResult(boolean success, Throwable error, List<MacroLogEntry> log) {
}