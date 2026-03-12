package com.btcpay.btcpayservercloverplugin;

import android.accounts.Account;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.StrictMode;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.clover.sdk.util.CloverAccount;
import com.clover.sdk.v1.ResultStatus;
import com.clover.sdk.v1.tender.Tender;
import com.clover.sdk.v1.tender.TenderConnector;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "BTCPayPrefs";
    private static final String KEY_URL = "btcpay_url";
    private static final String KEY_STORE_ID = "store_id";
    private static final String KEY_API_KEY = "api_key";

    private EditText editUrl, editStoreId, editApiKey;
    private TextView textConnectionStatus, textTenderStatus;
    private Button btnTestSave;
    private TenderConnector tenderConnector;
    private Account account;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        editUrl = findViewById(R.id.edit_btcpay_url);
        editStoreId = findViewById(R.id.edit_store_id);
        editApiKey = findViewById(R.id.edit_api_key);
        textConnectionStatus = findViewById(R.id.text_connection_status);
        textTenderStatus = findViewById(R.id.text_tender_status);
        btnTestSave = findViewById(R.id.btn_test_save);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        editUrl.setText(prefs.getString(KEY_URL, ""));
        editStoreId.setText(prefs.getString(KEY_STORE_ID, ""));
        editApiKey.setText(prefs.getString(KEY_API_KEY, ""));
        btnTestSave.setOnClickListener(v -> testAndSave());
        account = CloverAccount.getAccount(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (account != null) {
            tenderConnector = new TenderConnector(this, account, null);
            tenderConnector.connect();
        }
    }

    @Override
    protected void onPause() {
        if (tenderConnector != null) {
            tenderConnector.disconnect();
            tenderConnector = null;
        }
        super.onPause();
    }

    private void testAndSave() {
        String url = editUrl.getText().toString().trim();
        String storeId = editStoreId.getText().toString().trim();
        String apiKey = editApiKey.getText().toString().trim();

        if (url.isEmpty() || storeId.isEmpty() || apiKey.isEmpty()) {
            textConnectionStatus.setText("Please fill in all fields");
            textConnectionStatus.setTextColor(0xFFCC0000);
            return;
        }
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);

        btnTestSave.setEnabled(false);
        textConnectionStatus.setTextColor(0xFF888888);
        textConnectionStatus.setText("Testing connection...");

        final String finalUrl = url;
        final String finalStoreId = storeId;
        final String finalApiKey = apiKey;

        new Thread(() -> {
            boolean success = false;
            String message = "";
            try {
                String endpoint = finalUrl + "/api/v1/stores/" + finalStoreId;
                HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "token " + finalApiKey);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    JSONObject json = new JSONObject(sb.toString());
                    String storeName = json.optString("name", "Unknown Store");
                    success = true;
                    message = "Connected to: " + storeName;
                } else if (responseCode == 401) {
                    message = "Invalid API key";
                } else if (responseCode == 404) {
                    message = "Store ID not found";
                } else {
                    message = "Server returned: " + responseCode;
                }
            } catch (Exception e) {
                message = "Could not reach server: " + e.getMessage();
            }

            final boolean finalSuccess = success;
            final String finalMessage = message;

            runOnUiThread(() -> {
                btnTestSave.setEnabled(true);
                textConnectionStatus.setText(finalMessage);
                textConnectionStatus.setTextColor(finalSuccess ? 0xFF00AA00 : 0xFFCC0000);

                if (finalSuccess) {
                    SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                    editor.putString(KEY_URL, finalUrl);
                    editor.putString(KEY_STORE_ID, finalStoreId);
                    editor.putString(KEY_API_KEY, finalApiKey);
                    editor.apply();

                    // Register tender
                    registerTender();
                }
            });
        }).start();
    }

    private void registerTender() {
        if (tenderConnector == null) {
            textTenderStatus.setText("Clover service not available");
            textTenderStatus.setTextColor(0xFFCC0000);
            return;
        }

        textTenderStatus.setText("Registering BTCPay Server tender...");
        textTenderStatus.setTextColor(0xFF888888);

        tenderConnector.checkAndCreateTender(getString(R.string.tender_name), getPackageName(), true, false,
                new TenderConnector.TenderCallback<Tender>() {
                    @Override
                    public void onServiceSuccess(Tender result, ResultStatus status) {
                        runOnUiThread(() -> {
                            textTenderStatus.setText("BTCPay Server tender registered!");
                            textTenderStatus.setTextColor(0xFF00AA00);
                        });
                    }

                    @Override
                    public void onServiceFailure(ResultStatus status) {
                        runOnUiThread(() -> {
                            textTenderStatus.setText("Registration failed: " + status.getStatusMessage());
                            textTenderStatus.setTextColor(0xFFCC0000);
                        });
                    }

                    @Override
                    public void onServiceConnectionFailure() {
                        runOnUiThread(() -> {
                            textTenderStatus.setText("Could not bind to Android service");
                            textTenderStatus.setTextColor(0xFFCC0000);
                        });
                    }
                });
    }
}