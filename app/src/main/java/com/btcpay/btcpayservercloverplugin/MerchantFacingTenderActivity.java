package com.buffalodyl.btcpayservercloverplugin;


import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.clover.sdk.v1.Intents;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

public class MerchantFacingTenderActivity extends Activity {
    private static final String TAG = "MerchantTender";
    private TextView textAmount, textStatus, textTip;
    private ImageView imageQr;
    private Button btnCancel;

    private BTCPayApiClient btcPayClient;
    private String currentInvoiceId;
    private String orderId;
    private String merchantId;
    private String employeeId;
    private Currency currency;
    private long baseAmountCents;
    private long tipAmountCents;
    private long totalAmountCents;
    private boolean polling = false;
    private boolean finalizingPayment = false;

    private final Handler handler = new Handler();
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            checkInvoiceStatus();
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tender);
        setResult(RESULT_CANCELED);

        textAmount = findViewById(R.id.text_amount);
        textStatus = findViewById(R.id.text_status);
        textTip = findViewById(R.id.text_tip);
        imageQr = findViewById(R.id.image_qr);
        btnCancel = findViewById(R.id.btn_cancel);

        baseAmountCents = getIntent().getLongExtra(Intents.EXTRA_AMOUNT, 0);
        orderId = getIntent().getStringExtra(Intents.EXTRA_ORDER_ID);
        merchantId = getIntent().getStringExtra(Intents.EXTRA_MERCHANT_ID);
        employeeId = getIntent().getStringExtra(Intents.EXTRA_EMPLOYEE_ID);
        currency = (Currency) getIntent().getSerializableExtra(Intents.EXTRA_CURRENCY);
        if (currency == null) {
            textStatus.setText("Could not determine currency. Please try again.");
            return;
        }
        tipAmountCents = 0L;
        totalAmountCents = baseAmountCents;

        updateAmountDisplay();
        textStatus.setText("Waiting for tip selection...");
        textTip.setText(formatBreakdown());

        btcPayClient = new BTCPayApiClient(this);

        if (!btcPayClient.isConfigured()) {
            textStatus.setText("Kindly setup your BTCPay Server on this Clover PoS");
            return;
        }

        btnCancel.setOnClickListener(v -> {
            polling = false;
            handler.removeCallbacks(pollRunnable);
            setResult(RESULT_CANCELED);
            finish();
        });

        startTipFlow();
    }

    private void startTipFlow() {
        long fifteenPercent = calculatePercentageTip(15);
        long twentyPercent = calculatePercentageTip(20);
        long twentyFivePercent = calculatePercentageTip(25);
        CharSequence[] options = new CharSequence[] {
                "No tip",
                "15% (" + formatAmount(currency, fifteenPercent) + ")",
                "20% (" + formatAmount(currency, twentyPercent) + ")",
                "25% (" + formatAmount(currency, twentyFivePercent) + ")",
                "Custom amount"
        };

        new AlertDialog.Builder(this)
                .setTitle("Select tip")
                .setCancelable(false)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            applyTipSelection(0L);
                            break;
                        case 1:
                            applyTipSelection(fifteenPercent);
                            break;
                        case 2:
                            applyTipSelection(twentyPercent);
                            break;
                        case 3:
                            applyTipSelection(twentyFivePercent);
                            break;
                        default:
                            showCustomTipDialog();
                            break;
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    setResult(RESULT_CANCELED);
                    finish();
                })
                .show();
    }

    private void createInvoice() {
        textStatus.setText("Creating invoice...");
        new Thread(() -> {
            try {
                BTCPayApiClient.InvoiceResult invoice = btcPayClient.createInvoice(
                        totalAmountCents,
                        currency.getCurrencyCode(),
                        orderId,
                        merchantId,
                        employeeId,
                        baseAmountCents,
                        tipAmountCents);
                currentInvoiceId = invoice.invoiceId;

                int sizePx = (int) (getResources().getDisplayMetrics().density * 250);
                Bitmap qr = QRCodeHelper.generateQRCode(invoice.paymentPayload, sizePx);

                runOnUiThread(() -> {
                    textStatus.setText("Scan QR to pay using " + formatPaymentMethod(invoice.paymentMethodId));
                    if (qr != null) {
                        imageQr.setImageBitmap(qr);
                        imageQr.setVisibility(View.VISIBLE);
                    }
                    startPolling();
                });
            } catch (Exception e) {
                runOnUiThread(() -> textStatus.setText("Error: " + e.getMessage()));
            }
        }).start();
    }

    private void updateAmountDisplay() {
        textAmount.setText("Pay with Bitcoin  " + formatAmount(currency, totalAmountCents));
        textTip.setText("Tip: " + formatAmount(currency, tipAmountCents));
    }

    private long calculatePercentageTip(int percent) {
        return Math.round(baseAmountCents * (percent / 100.0));
    }

    private void applyTipSelection(long selectedTipCents) {
        tipAmountCents = Math.max(selectedTipCents, 0L);
        totalAmountCents = baseAmountCents + tipAmountCents;
        updateAmountDisplay();
        createInvoice();
    }

    private void showCustomTipDialog() {
        EditText input = new EditText(this);
        input.setHint("0.00");
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Custom tip")
                .setMessage("Enter tip amount in " + currency.getCurrencyCode())
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Apply", null)
                .setNegativeButton("Back", (d, which) -> startTipFlow())
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                BigDecimal entered = new BigDecimal(input.getText().toString().trim());
                long customTipCents = entered
                        .movePointRight(2)
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact();
                dialog.dismiss();
                applyTipSelection(customTipCents);
            } catch (Exception e) {
                input.setError("Enter a valid amount");
            }
        });
    }

    private void startPolling() {
        polling = true;
        handler.post(pollRunnable);
    }

    private void checkInvoiceStatus() {
        if (currentInvoiceId == null) return;
        new Thread(() -> {
            try {
                String status = btcPayClient.getInvoiceStatus(currentInvoiceId);
                runOnUiThread(() -> handleStatus(status));
            } catch (Exception e) {
                // silently retry
            }
        }).start();
    }

    private void handleStatus(String status) {
        switch (status) {
            case "Settled":
            case "Processing":
                if (finalizingPayment) {
                    return;
                }
                finalizingPayment = true;
                polling = false;
                handler.removeCallbacks(pollRunnable);
                textStatus.setText("Payment received. Finalizing Clover sale...");
                new Thread(() -> finalizeCloverSale()).start();
                break;
            case "Expired":
            case "Invalid":
                polling = false;
                handler.removeCallbacks(pollRunnable);
                textStatus.setText("Invoice expired. Tap Cancel.");
                break;
        }
    }

    private String formatAmount(Currency currency, long amountCents) {
        if (currency == null) return String.format("%.2f", amountCents / 100.0);
        return currency.getSymbol() + String.format("%.2f", amountCents / 100.0);
    }

    private String formatBreakdown() {
        return "Subtotal: " + formatAmount(currency, baseAmountCents)
                + "   Tip: " + formatAmount(currency, tipAmountCents);
    }

    private String formatPaymentMethod(String paymentMethodId) {
        if ("BTC-LN".equals(paymentMethodId)) return "Lightning";
        if ("BTC-CHAIN".equals(paymentMethodId)) return "Bitcoin";
        if ("BTC-LNURL".equals(paymentMethodId)) return "LNURL";
        return "BTCPay Server";
    }

    private void finalizeCloverSale() {
        try {
            new CloverPaymentRecorder(this).recordPayment(
                    orderId,
                    employeeId,
                    currentInvoiceId,
                    baseAmountCents,
                    tipAmountCents);
            runOnUiThread(() -> {
                textStatus.setText("Payment recorded in Clover!");
                setResult(RESULT_CANCELED);
                finish();
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to record Clover payment", e);
            runOnUiThread(() -> {
                textStatus.setText("Clover record failed: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            });
        }
    }

    @Override
    protected void onDestroy() {
        polling = false;
        handler.removeCallbacks(pollRunnable);
        super.onDestroy();
    }
}
