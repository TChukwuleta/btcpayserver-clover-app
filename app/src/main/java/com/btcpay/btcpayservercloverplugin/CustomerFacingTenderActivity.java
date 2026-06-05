package com.btcpay.btcpayservercloverplugin;

import android.accounts.Account;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.clover.sdk.util.CloverAccount;
import com.clover.sdk.v1.Intents;
import com.clover.sdk.v1.ResultStatus;
import com.clover.sdk.v3.employees.Employee;
import com.clover.sdk.v3.employees.EmployeeConnector;

import java.util.Currency;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class CustomerFacingTenderActivity extends Activity {

    private static final String TAG = "BTCPayTenderCustomer";

    private TextView textAmount, textStatus, textSubtitle, textSubtotal, textTip;
    private ImageView imageQr;
    private Button btnCancel;

    private BTCPayApiClient btcPayClient;
    private EmployeeConnector employeeConnector;

    private String currentInvoiceId;
    private String orderId;
    private String merchantId;
    private String employeeId;
    private String employeeName;
    private Currency currency;

    private long baseAmountCents;
    private long tipAmountCents = 0;
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
        setContentView(R.layout.activity_tender_customer);
        setResult(RESULT_CANCELED);
        setSystemUiVisibility();

        textAmount = findViewById(R.id.text_amount);
        textSubtitle = findViewById(R.id.text_subtitle);
        textStatus = findViewById(R.id.text_status);
        textSubtotal = findViewById(R.id.text_subtotal);
        textTip = findViewById(R.id.text_tip);
        imageQr = findViewById(R.id.image_qr);
        btnCancel = findViewById(R.id.btn_cancel);

        baseAmountCents = getIntent().getLongExtra(Intents.EXTRA_AMOUNT, 0);
        orderId = getIntent().getStringExtra(Intents.EXTRA_ORDER_ID);
        merchantId = getIntent().getStringExtra(Intents.EXTRA_MERCHANT_ID);
        employeeId = getIntent().getStringExtra(Intents.EXTRA_EMPLOYEE_ID);
        currency = (Currency) getIntent().getSerializableExtra(Intents.EXTRA_CURRENCY);

        Log.i(TAG, "onCreate amount=" + baseAmountCents
                + " orderId=" + orderId
                + " employeeId=" + employeeId
                + " currency=" + (currency == null ? "null" : currency.getCurrencyCode()));

        if (currency == null) {
            textStatus.setText("Could not determine currency. Please try again.");
            return;
        }

        totalAmountCents = baseAmountCents;
        updateAmountDisplay();

        String currencyCode = currency.getCurrencyCode();
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

        // Fetch employee name, then show tip dialog
        Account account = CloverAccount.getAccount(this);
        employeeConnector = new EmployeeConnector(this, account, null);
        employeeConnector.connect();

        employeeConnector.getEmployee(employeeId, new EmployeeConnector.EmployeeCallback<Employee>() {
            @Override
            public void onServiceSuccess(Employee employee, ResultStatus status) {
                employeeName = employee.getName();
                runOnUiThread(() -> showTipDialog());
            }

            @Override
            public void onServiceFailure(ResultStatus status) {
                runOnUiThread(() -> showTipDialog());
            }

            @Override
            public void onServiceConnectionFailure() {
                runOnUiThread(() -> showTipDialog());
            }
        });
    }

    private void showTipDialog() {
        BTCPayApiClient.Config config = BTCPayApiClient.loadConfiguration(this);
        if (!config.tippingEnabled) {
            // Tipping disabled by admin — go straight to invoice
            applyTipSelection(0L);
            return;
        }

        textStatus.setText("Select tip to continue...");

        long tip15 = calculatePercentageTip(15);
        long tip20 = calculatePercentageTip(20);
        long tip25 = calculatePercentageTip(25);

        CharSequence[] options = {
                "No tip",
                "15% (" + formatAmount(currency, tip15) + ")",
                "20% (" + formatAmount(currency, tip20) + ")",
                "25% (" + formatAmount(currency, tip25) + ")",
                "Custom amount"
        };

        new AlertDialog.Builder(this)
                .setTitle("Select tip")
                .setCancelable(false)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: applyTipSelection(0L); break;
                        case 1: applyTipSelection(tip15); break;
                        case 2: applyTipSelection(tip20); break;
                        case 3: applyTipSelection(tip25); break;
                        default: showCustomTipDialog(); break;
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    setResult(RESULT_CANCELED);
                    finish();
                })
                .show();
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
                .setNegativeButton("Back", (d, which) -> showTipDialog())
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

    private void applyTipSelection(long selectedTipCents) {
        tipAmountCents = Math.max(selectedTipCents, 0L);
        totalAmountCents = baseAmountCents + tipAmountCents;
        Log.i(TAG, "applyTipSelection tipAmount=" + tipAmountCents + " totalAmount=" + totalAmountCents);
        updateAmountDisplay();
        createInvoice();
    }

    private void createInvoice() {
        textSubtitle.setText("Scan with your phone to pay");
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
                Log.e(TAG, "Invoice creation failed", e);
                runOnUiThread(() -> textStatus.setText("Error: " + e.getMessage()));
            }
        }).start();
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
                if (finalizingPayment) return;
                finalizingPayment = true;
                polling = false;
                handler.removeCallbacks(pollRunnable);
                textStatus.setText("Payment received!");
                imageQr.setVisibility(View.GONE);
                Intent data = new Intent();
                data.putExtra(Intents.EXTRA_AMOUNT, totalAmountCents);
                data.putExtra(Intents.EXTRA_TIP_AMOUNT, tipAmountCents);
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

    private void updateAmountDisplay() {
        textAmount.setText(formatAmount(currency, totalAmountCents));
        textSubtotal.setText("Subtotal: " + formatAmount(currency, baseAmountCents));
        textTip.setText("Tip: " + formatAmount(currency, tipAmountCents));
    }

    private long calculatePercentageTip(int percent) {
        return Math.round(baseAmountCents * (percent / 100.0));
    }

    private String formatAmount(Currency currency, long amountCents) {
        if (currency == null) return String.format("%.2f", amountCents / 100.0);
        return currency.getSymbol() + String.format("%.2f", amountCents / 100.0);
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


    @Override
    protected void onDestroy() {
        polling = false;
        handler.removeCallbacks(pollRunnable);
        if (employeeConnector != null) {
            employeeConnector.disconnect();
            employeeConnector = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Block back press on customer screen
    }
}