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
import java.util.Currency;

public class CustomerFacingTenderActivity extends Activity {

    private TextView textAmount, textStatus, textSubtitle;
    private ImageView imageQr;
    private Button btnCancel;

    private BTCPayApiClient btcPayClient;
    private String currentInvoiceId;
    private long amountCents;
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
        setContentView(R.layout.activity_tender_customer);
        setResult(RESULT_CANCELED);
        setSystemUiVisibility();

        amountCents = getIntent().getLongExtra(Intents.EXTRA_AMOUNT, 0);
        String orderId = getIntent().getStringExtra(Intents.EXTRA_ORDER_ID);
        String merchantId = getIntent().getStringExtra(Intents.EXTRA_MERCHANT_ID);
        Currency currency = (Currency) getIntent().getSerializableExtra(Intents.EXTRA_CURRENCY);
        if (currency == null) {
            textStatus.setText("Could not determine currency. Please try again.");
            return;
        }
        String currencyCode = currency.getCurrencyCode();

        textAmount = findViewById(R.id.text_amount);
        textSubtitle = findViewById(R.id.text_subtitle);
        textStatus = findViewById(R.id.text_status);
        imageQr = findViewById(R.id.image_qr);
        btnCancel = findViewById(R.id.btn_cancel);

        textAmount.setText(formatAmount(currency, amountCents));
        textSubtitle.setText("Scan with your phone to pay");
        textStatus.setText("Creating invoice...");

        btcPayClient = new BTCPayApiClient(this);

        if (!btcPayClient.isConfigured()) {
            textStatus.setText("BTCPay not configured.");
            return;
        }

        btnCancel.setOnClickListener(v -> {
            polling = false;
            handler.removeCallbacks(pollRunnable);
            setResult(RESULT_CANCELED);
            finish();
        });

        new Thread(() -> {
            try {
                BTCPayApiClient.InvoiceResult invoice = btcPayClient.createInvoice(amountCents, currencyCode, orderId, merchantId);
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

    public void setSystemUiVisibility() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LOW_PROFILE
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
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
                imageQr.setVisibility(View.GONE);
                Intent data = new Intent();
                data.putExtra(Intents.EXTRA_AMOUNT, amountCents);
                data.putExtra(Intents.EXTRA_CLIENT_ID, currentInvoiceId);
                setResult(RESULT_OK, data);
                handler.postDelayed(this::finish, 3000);
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
    public void onBackPressed() {
        // Block back press on customer screen
    }
}