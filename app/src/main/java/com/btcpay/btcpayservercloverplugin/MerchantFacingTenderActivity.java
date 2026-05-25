package com.btcpay.btcpayservercloverplugin;


import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.clover.sdk.v1.Intents;
import com.clover.sdk.v3.payments.api.RequestTipIntentBuilder;

import java.util.Currency;

public class MerchantFacingTenderActivity extends Activity {
    private static final int TIP_REQUEST_CODE = 1001;

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
        textTip.setText("Tip: " + formatAmount(currency, tipAmountCents));

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
        try {
            Intent intent = new RequestTipIntentBuilder(baseAmountCents).build(this);
            startActivityForResult(intent, TIP_REQUEST_CODE);
        } catch (Exception e) {
            textStatus.setText("Could not start tip screen: " + e.getMessage());
        }
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
                Bitmap qr = QRCodeHelper.generateQRCode(invoice.checkoutUrl, sizePx);

                runOnUiThread(() -> {
                    textStatus.setText("Scan QR to pay using BTCPay Server");
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
                polling = false;
                handler.removeCallbacks(pollRunnable);
                textStatus.setText("Payment received!");
                Intent data = new Intent();
                data.putExtra(Intents.EXTRA_AMOUNT, totalAmountCents);
                data.putExtra(Intents.EXTRA_TIP_AMOUNT, tipAmountCents);
                data.putExtra(Intents.EXTRA_CLIENT_ID, currentInvoiceId);
                setResult(RESULT_OK, data);
                finish();
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

    @Override
    protected void onDestroy() {
        polling = false;
        handler.removeCallbacks(pollRunnable);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != TIP_REQUEST_CODE) {
            return;
        }

        if (resultCode != RESULT_OK || data == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        tipAmountCents = data.getLongExtra(RequestTipIntentBuilder.Response.TIP_AMOUNT, 0L);
        totalAmountCents = baseAmountCents + tipAmountCents;
        updateAmountDisplay();
        createInvoice();
    }
}
