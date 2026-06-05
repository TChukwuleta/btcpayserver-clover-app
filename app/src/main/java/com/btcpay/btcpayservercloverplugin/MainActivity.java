package com.btcpay.btcpayservercloverplugin;

import android.accounts.Account;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.StrictMode;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.clover.sdk.util.CloverAccount;
import com.clover.sdk.v1.ResultStatus;
import com.clover.sdk.v1.tender.Tender;
import com.clover.sdk.v1.tender.TenderConnector;
import com.clover.sdk.v3.employees.AccountRole;
import com.clover.sdk.v3.employees.Employee;
import com.clover.sdk.v3.employees.EmployeeConnector;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BTCPayMain";

    private EditText editUrl, editStoreId, editApiKey;
    private TextView textConnectionStatus, textTenderStatus, textAccessDenied;
    private Switch switchTipping;
    private Button btnTestSave;
    private LinearLayout layoutSettings;

    private TenderConnector tenderConnector;
    private EmployeeConnector employeeConnector;
    private Account account;
    private boolean tenderRegistrationRequested = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editUrl = findViewById(R.id.edit_btcpay_url);
        editStoreId = findViewById(R.id.edit_store_id);
        editApiKey = findViewById(R.id.edit_api_key);
        textConnectionStatus = findViewById(R.id.text_connection_status);
        textTenderStatus = findViewById(R.id.text_tender_status);
        textAccessDenied = findViewById(R.id.text_access_denied);
        switchTipping = findViewById(R.id.switch_tipping);
        btnTestSave = findViewById(R.id.btn_test_save);
        layoutSettings = findViewById(R.id.layout_settings);

        account = CloverAccount.getAccount(this);

        BTCPayApiClient.Config config = BTCPayApiClient.loadConfiguration(this);
        editUrl.setText(config.baseUrl);
        editStoreId.setText(config.storeId);
        editApiKey.setText(config.apiKey);
        switchTipping.setChecked(config.tippingEnabled);

        btnTestSave.setOnClickListener(v -> testAndSave());
        checkEmployeeRole();
    }

    private void checkEmployeeRole() {
        layoutSettings.setVisibility(View.GONE);
        textAccessDenied.setVisibility(View.GONE);

        EmployeeConnector connector = new EmployeeConnector(this, account, null);
        connector.connect();

        connector.getEmployee(new EmployeeConnector.EmployeeCallback<Employee>() {
            @Override
            public void onServiceSuccess(Employee employee, ResultStatus status) {
                boolean privileged = isPrivilegedEmployee(employee);
                runOnUiThread(() -> {
                    if (privileged) {
                        showSettings();
                    } else {
                        showAccessDenied();
                    }
                    connector.disconnect();
                });
            }

            @Override
            public void onServiceFailure(ResultStatus status) {
                runOnUiThread(() -> {
                    showAccessDenied();
                    connector.disconnect();
                });
            }

            @Override
            public void onServiceConnectionFailure() {
                runOnUiThread(() -> {
                    showAccessDenied();
                    connector.disconnect();
                });
            }
        });
    }

    private boolean isPrivilegedEmployee(Employee employee) {
        if (employee == null) return false;
        if (Boolean.TRUE.equals(employee.getIsOwner())) return true;
        AccountRole role = employee.getRole();
        return role == AccountRole.ADMIN || role == AccountRole.MANAGER;
    }

    private void showSettings() {
        layoutSettings.setVisibility(View.VISIBLE);
        textAccessDenied.setVisibility(View.GONE);
    }

    private void showAccessDenied() {
        layoutSettings.setVisibility(View.GONE);
        textAccessDenied.setVisibility(View.VISIBLE);
        textAccessDenied.setText("Only managers and admins can configure BTCPay settings.");
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
            tenderRegistrationRequested = false;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (employeeConnector != null) {
            employeeConnector.disconnect();
            employeeConnector = null;
        }
        super.onDestroy();
    }

    private void testAndSave() {
        String url = editUrl.getText().toString().trim();
        String storeId = editStoreId.getText().toString().trim();
        String apiKey = editApiKey.getText().toString().trim();
        boolean tippingEnabled = switchTipping.isChecked();

        if (url.isEmpty() || storeId.isEmpty() || apiKey.isEmpty()) {
            textConnectionStatus.setText("Please fill in all fields");
            textConnectionStatus.setTextColor(0xFFCC0000);
            return;
        }

        btnTestSave.setEnabled(false);
        textConnectionStatus.setTextColor(0xFF888888);
        textConnectionStatus.setText("Testing connection...");

        new Thread(() -> {
            boolean success = false;
            String message = "";
            try {
                String storeName = BTCPayApiClient.testConnection(url, storeId, apiKey);
                success = true;
                message = "Connected to: " + storeName;
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
                    BTCPayApiClient.saveConfiguration(this, url, storeId, apiKey, tippingEnabled);
                    tenderRegistrationRequested = false;
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

        if (tenderRegistrationRequested) return;
        tenderRegistrationRequested = true;

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

                        new Thread(() -> {
                            try {
                                new CloverTenderConfigurator(MainActivity.this).ensureSupportsTipping(result);
                                Log.i(TAG, "Tipping enabled on tender");
                            } catch (Exception e) {
                                Log.w(TAG, "Could not enable tipping on tender: " + e.getMessage());
                            }
                        }).start();
                    }

                    @Override
                    public void onServiceFailure(ResultStatus status) {
                        tenderRegistrationRequested = false;
                        runOnUiThread(() -> {
                            textTenderStatus.setText("Registration failed: " + status.getStatusMessage());
                            textTenderStatus.setTextColor(0xFFCC0000);
                        });
                    }

                    @Override
                    public void onServiceConnectionFailure() {
                        tenderRegistrationRequested = false;
                        runOnUiThread(() -> {
                            textTenderStatus.setText("Could not bind to Android service");
                            textTenderStatus.setTextColor(0xFFCC0000);
                        });
                    }
                });


    }
}