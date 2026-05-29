package com.buffalodyl.btcpayservercloverplugin;

public final class TipFlowConfig {
    // Active tip collection strategy for custom tender testing.
    // LOCAL_DIALOG is the current default because Clover's native RequestTip flow
    // crashes on the emulator in com.clover.payment.builder.pay.
    // Switch this constant and rebuild to retest CLOVER_NATIVE later.
    private static final TipFlowMode ACTIVE_TIP_FLOW = TipFlowMode.LOCAL_DIALOG;

    private TipFlowConfig() {
    }

    public static TipFlowMode getActiveTipFlow() {
        return ACTIVE_TIP_FLOW;
    }
}
