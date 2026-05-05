package com.opentermx.macro;

public interface MacroUiBridge {
    void showMessage(String message);

    String prompt(String message, String defaultValue);

    String getClipboard();

    void setClipboard(String text);

    final class NoOp implements MacroUiBridge {
        @Override public void showMessage(String message) {}
        @Override public String prompt(String message, String defaultValue) { return defaultValue; }
        @Override public String getClipboard() { return ""; }
        @Override public void setClipboard(String text) {}
    }
}