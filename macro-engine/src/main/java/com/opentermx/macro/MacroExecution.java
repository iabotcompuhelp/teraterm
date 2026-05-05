package com.opentermx.macro;

import java.util.concurrent.atomic.AtomicReference;

public final class MacroExecution {

    private final Thread thread;
    private final MacroContext context;
    private final AtomicReference<MacroResult> result = new AtomicReference<>();

    MacroExecution(Thread thread, MacroContext context) {
        this.thread = thread;
        this.context = context;
    }

    public boolean isRunning() {
        return thread.isAlive();
    }

    public MacroResult getResult() {
        return result.get();
    }

    void setResult(MacroResult r) {
        result.set(r);
    }

    public void cancel() {
        context.cancel();
        thread.interrupt();
    }

    public void await() throws InterruptedException {
        thread.join();
    }

    public boolean await(long timeoutMillis) throws InterruptedException {
        thread.join(timeoutMillis);
        return !thread.isAlive();
    }
}