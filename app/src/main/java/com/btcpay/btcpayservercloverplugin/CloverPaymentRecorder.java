package com.buffalodyl.btcpayservercloverplugin;

import android.accounts.Account;
import android.content.Context;
import android.util.Log;

import com.clover.sdk.util.CloverAccount;
import com.clover.sdk.v1.tender.Tender;
import com.clover.sdk.v1.tender.TenderConnector;
import com.clover.sdk.v3.base.Reference;
import com.clover.sdk.v3.order.OrderConnector;
import com.clover.sdk.v3.payments.Payment;
import com.clover.sdk.v3.payments.Result;

import java.util.List;

public class CloverPaymentRecorder {
    private static final String TAG = "CloverPaymentRecorder";
    private final Context context;

    public CloverPaymentRecorder(Context context) {
        this.context = context;
    }

    public void recordPayment(String orderId, String employeeId, String externalPaymentId,
                              long baseAmountCents, long tipAmountCents) throws Exception {
        Log.i(TAG, "Recording Clover payment orderId=" + orderId
                + " externalPaymentId=" + externalPaymentId
                + " baseAmountCents=" + baseAmountCents
                + " tipAmountCents=" + tipAmountCents
                + " employeeId=" + employeeId);
        if (orderId == null || orderId.isEmpty()) {
            throw new IllegalArgumentException("Missing Clover order id");
        }
        if (externalPaymentId == null || externalPaymentId.isEmpty()) {
            throw new IllegalArgumentException("Missing external payment id");
        }

        Account account = CloverAccount.getAccount(context);
        if (account == null) {
            throw new IllegalStateException("No Clover account available");
        }

        TenderConnector tenderConnector = new TenderConnector(context, account, null);
        OrderConnector orderConnector = new OrderConnector(context, account, null);

        tenderConnector.connect();
        orderConnector.connect();
        try {
            Tender tender = findTender(tenderConnector.getTenders());
            if (tender == null || tender.getId() == null) {
                throw new IllegalStateException("Could not locate BTCPay tender");
            }

            Payment payment = new Payment();
            payment.setOrder(new Reference().setId(orderId));
            payment.setTender(new com.clover.sdk.v3.base.Tender()
                    .setId(tender.getId())
                    .setLabel(tender.getLabel())
                    .setLabelKey(tender.getLabelKey()));
            payment.setAmount(baseAmountCents);
            if (tipAmountCents > 0) {
                payment.setTipAmount(tipAmountCents);
            }
            payment.setTaxAmount(0L);
            payment.setExternalPaymentId(externalPaymentId);
            payment.setCreatedTime(System.currentTimeMillis());
            payment.setResult(Result.SUCCESS);
            payment.setNote("BTCPay invoice " + externalPaymentId);
            if (employeeId != null && !employeeId.isEmpty()) {
                payment.setEmployee(new Reference().setId(employeeId));
            }

            orderConnector.addPayment3(orderId, payment, null, true);
            Log.i(TAG, "Clover payment recorded successfully for orderId=" + orderId);
        } finally {
            orderConnector.disconnect();
            tenderConnector.disconnect();
        }
    }

    private Tender findTender(List<Tender> tenders) {
        if (tenders == null) {
            return null;
        }
        for (Tender tender : tenders) {
            if (context.getPackageName().equals(tender.getLabelKey())) {
                return tender;
            }
        }
        return null;
    }
}
