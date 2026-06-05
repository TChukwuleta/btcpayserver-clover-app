package com.btcpay.btcpayservercloverplugin;


import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class BTCPayApiClient {

    private static final String PREFS_NAME = "BTCPayPrefs";
    private static final String KEY_URL = "btcpay_url";
    private static final String KEY_STORE_ID = "store_id";
    private static final String KEY_API_KEY = "api_key";
    public static final String KEY_TIPPING_ENABLED = "tipping_enabled";

    private final String baseUrl;
    private final String storeId;
    private final String apiKey;

    public boolean isConfigured() {
        return !baseUrl.isEmpty() && !storeId.isEmpty() && !apiKey.isEmpty();
    }


    public static class Config {
        public final String baseUrl;
        public final String storeId;
        public final String apiKey;
        public final boolean tippingEnabled;

        public Config(String baseUrl, String storeId, String apiKey, boolean tippingEnabled) {
            this.baseUrl = baseUrl;
            this.storeId = storeId;
            this.apiKey = apiKey;
            this.tippingEnabled = tippingEnabled;
        }
    }

    public static class InvoiceResult {
        public String invoiceId;
        public String checkoutUrl;
        public InvoiceResult(String invoiceId, String checkoutUrl) {
            this.invoiceId = invoiceId;
            this.checkoutUrl = checkoutUrl;
        }
    }

    public BTCPayApiClient(Context context) {
        Config config = loadConfiguration(context);
        this.baseUrl = config.baseUrl;
        this.storeId = config.storeId;
        this.apiKey = config.apiKey;
    }

    public static Config loadConfiguration(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return new Config(
                sanitizeBaseUrl(prefs.getString(KEY_URL, "")),
                prefs.getString(KEY_STORE_ID, ""),
                prefs.getString(KEY_API_KEY, ""),
                prefs.getBoolean(KEY_TIPPING_ENABLED, true)
        );
    }

    public static void saveConfiguration(Context context, String baseUrl, String storeId, String apiKey, boolean tippingEnabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_URL, sanitizeBaseUrl(baseUrl))
                .putString(KEY_STORE_ID, storeId == null ? "" : storeId.trim())
                .putString(KEY_API_KEY, apiKey == null ? "" : apiKey.trim())
                .putBoolean(KEY_TIPPING_ENABLED, tippingEnabled)
                .apply();
    }

    public static String testConnection(String baseUrl, String storeId, String apiKey) throws Exception {
        String endpoint = sanitizeBaseUrl(baseUrl) + "/api/v1/stores/" + storeId.trim();

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "token " + apiKey.trim());
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP " + responseCode + " " + readErrorResponseStatic(conn));
        }

        JSONObject json = new JSONObject(readResponseStatic(conn));
        return json.optString("name", storeId);
    }

    public InvoiceResult createInvoice(long amountCents, String currency, String orderId, String merchantId,
                                       String employeeId, String employeeName, long baseAmountCents, long tipAmountCents) throws Exception {

        String endpoint = baseUrl + "/api/v1/stores/" + storeId + "/invoices";
        JSONObject body = new JSONObject();
        body.put("amount", amountCents / 100.0);
        body.put("currency", currency);

        JSONObject metadata = new JSONObject();
        if (orderId != null) metadata.put("orderId", orderId);
        if (merchantId != null) metadata.put("itemDesc", "Clover Merchant: " + merchantId);
        if (employeeId != null) metadata.put("employeeId", employeeId);
        if (employeeName != null) metadata.put("employeeName", employeeName);
        metadata.put("baseAmount", baseAmountCents / 100.0);
        metadata.put("tipAmount", tipAmountCents / 100.0);
        metadata.put("source", "clover-custom-tender");
        body.put("metadata", metadata);

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "token " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes("UTF-8"));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200 && responseCode != 201) {
            throw new Exception("Failed to create invoice: HTTP " + responseCode + " " + readErrorResponse(conn));
        }

        JSONObject json = new JSONObject(readResponse(conn));
        String invoiceId = json.getString("id");
        String checkoutUrl = json.getString("checkoutLink");
        return new InvoiceResult(invoiceId, checkoutUrl);
    }

    public String getInvoiceStatus(String invoiceId) throws Exception {
        String endpoint = baseUrl + "/api/v1/stores/" + storeId + "/invoices/" + invoiceId;

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "token " + apiKey);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("Failed to get invoice status: HTTP " + responseCode + " " + readErrorResponse(conn));
        }

        JSONObject json = new JSONObject(readResponse(conn));
        return json.getString("status");
    }

    public void invalidateInvoice(String invoiceId) throws Exception {
        setInvoiceStatus(invoiceId, "Invalid");
    }

    public void setInvoiceStatus(String invoiceId, String status) throws Exception {
        String endpoint = baseUrl + "/api/v1/stores/" + storeId + "/invoices/" + invoiceId + "/status";

        JSONObject body = new JSONObject();
        body.put("status", status);

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "token " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes("UTF-8"));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("Failed to update invoice: HTTP " + responseCode + " " + readErrorResponse(conn));
        }
    }

    private static String sanitizeBaseUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String readResponseStatic(HttpURLConnection conn) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private String readErrorResponse(HttpURLConnection conn) {
        try {
            if (conn.getErrorStream() == null) {
                return "";
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String readErrorResponseStatic(HttpURLConnection conn) {
        try {
            if (conn.getErrorStream() == null) {
                return "";
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}