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

    private final String baseUrl;
    private final String storeId;
    private final String apiKey;

    public static class InvoiceResult {
        public String invoiceId;
        public String checkoutUrl;
        public InvoiceResult(String invoiceId, String checkoutUrl) {
            this.invoiceId = invoiceId;
            this.checkoutUrl = checkoutUrl;
        }
    }

    public BTCPayApiClient(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.baseUrl = prefs.getString(KEY_URL, "");
        this.storeId = prefs.getString(KEY_STORE_ID, "");
        this.apiKey = prefs.getString(KEY_API_KEY, "");
    }

    public boolean isConfigured() {
        return !baseUrl.isEmpty() && !storeId.isEmpty() && !apiKey.isEmpty();
    }

    public InvoiceResult createInvoice(long amountCents, String currency, String orderId, String merchantId) throws Exception {
        String endpoint = baseUrl + "/api/v1/stores/" + storeId + "/invoices";

        JSONObject body = new JSONObject();
        body.put("amount", amountCents / 100.0);
        body.put("currency", currency);

        JSONObject metadata = new JSONObject();
        if (orderId != null) metadata.put("orderId", orderId);
        if (merchantId != null) metadata.put("itemDesc", "Clover Merchant: " + merchantId);
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
            throw new Exception("Failed to create invoice: HTTP " + responseCode);
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
            throw new Exception("Failed to get invoice status: HTTP " + responseCode);
        }

        JSONObject json = new JSONObject(readResponse(conn));
        return json.getString("status");
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }
}