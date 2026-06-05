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

import com.clover.sdk.v1.Intents;

import com.clover.sdk.util.CloverAccount;
import com.clover.sdk.v1.ResultStatus;
import com.clover.sdk.v3.employees.Employee;
import com.clover.sdk.v3.employees.EmployeeConnector;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

public class MerchantFacingTenderActivity extends Activity {

    private static final String TAG = "BTCPayTenderMerchant";
    private TextView textAmount, textStatus, textSubtotal, textTip;
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
        setContentView(R.layout.activity_tender);
        setResult(RESULT_CANCELED);

        // Bind views first so errors can be shown on textStatus
        textAmount = findViewById(R.id.text_amount);
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
                + " merchantId=" + merchantId
                + " employeeId=" + employeeId
                + " currency=" + (currency == null ? "null" : currency.getCurrencyCode()));

        if (currency == null) {
            textStatus.setText("Could not determine currency. Please try again.");
            Log.w(TAG, "onCreate missing currency; leaving tender in canceled state");
            return;
        }

        totalAmountCents = baseAmountCents;
        updateAmountDisplay();
        textStatus.setText("Waiting for tip selection...");

        btcPayClient = new BTCPayApiClient(this);
        if (!btcPayClient.isConfigured()) {
            textStatus.setText("Kindly setup your BTCPay Server on this Clover PoS");
            return;
        }

        btnCancel.setOnClickListener(v -> {
            Log.i(TAG, "Cancel clicked currentInvoiceId=" + currentInvoiceId + " polling=" + polling);
            polling = false;
            handler.removeCallbacks(pollRunnable);
            setResult(RESULT_CANCELED);
            finish();
        });

        // Fetch employee name then show tip dialog
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
                    Log.i(TAG, "Tip dialog canceled");
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
                Log.i(TAG, "Custom tip entered tipAmount=" + customTipCents);
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
        textStatus.setText("Creating invoice...");
        Log.i(TAG, "createInvoice baseAmount=" + baseAmountCents
                + " tipAmount=" + tipAmountCents
                + " totalAmount=" + totalAmountCents
                + " orderId=" + orderId);

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
                Log.i(TAG, "Invoice created invoiceId=" + currentInvoiceId);

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
        Log.i(TAG, "startPolling invoiceId=" + currentInvoiceId);
        handler.post(pollRunnable);
    }

    private void checkInvoiceStatus() {
        if (currentInvoiceId == null) return;
        new Thread(() -> {
            try {
                String status = btcPayClient.getInvoiceStatus(currentInvoiceId);
                Log.d(TAG, "Invoice status invoiceId=" + currentInvoiceId + " status=" + status);
                runOnUiThread(() -> handleStatus(status));
            } catch (Exception e) {
                Log.w(TAG, "Invoice status check failed invoiceId=" + currentInvoiceId, e);
            }
        }).start();
    }

    private void handleStatus(String status) {
        Log.i(TAG, "handleStatus status=" + status
                + " finalizingPayment=" + finalizingPayment
                + " invoiceId=" + currentInvoiceId);
        switch (status) {
            case "Settled":
            case "Processing":
                if (finalizingPayment) {
                    Log.i(TAG, "Already finalizing; ignoring duplicate status=" + status);
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
                Log.w(TAG, "Invoice ended without payment status=" + status + " invoiceId=" + currentInvoiceId);
                break;
        }
    }

    private void updateAmountDisplay() {
        textAmount.setText("Pay with Bitcoin  " + formatAmount(currency, totalAmountCents));
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

    private void finalizeCloverSale() {
        runOnUiThread(() -> {
            textStatus.setText("Payment recorded in Clover!");
            Intent data = new Intent();
            data.putExtra(Intents.EXTRA_AMOUNT, totalAmountCents);
            data.putExtra(Intents.EXTRA_TIP_AMOUNT, tipAmountCents);
            data.putExtra(Intents.EXTRA_CLIENT_ID, currentInvoiceId);
            setResult(RESULT_OK, data);
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy invoiceId=" + currentInvoiceId
                + " polling=" + polling
                + " finalizingPayment=" + finalizingPayment
                + " isFinishing=" + isFinishing());
        polling = false;
        handler.removeCallbacks(pollRunnable);
        if (employeeConnector != null) {
            employeeConnector.disconnect();
            employeeConnector = null;
        }
        super.onDestroy();
    }
}