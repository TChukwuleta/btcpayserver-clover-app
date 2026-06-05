package com.btcpay.btcpayservercloverplugin;

import android.content.Context;
import android.util.Log;

import com.clover.sdk.util.CloverAuth;
import com.clover.sdk.v1.tender.Tender;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class CloverTenderConfigurator {
    private static final String TAG = "CloverTenderConfig";
    private final Context context;

    public CloverTenderConfigurator(Context context) {
        this.context = context;
    }

    public void ensureSupportsTipping(Tender tender) throws Exception {
        if (tender == null || tender.getId() == null || tender.getId().isEmpty()) {
            throw new IllegalArgumentException("Missing Clover tender id");
        }

        CloverAuth.AuthResult authResult = CloverAuth.authenticate(context, false, 20L, TimeUnit.SECONDS);
        if (authResult.authToken == null || authResult.baseUrl == null || authResult.merchantId == null) {
            throw new IllegalStateException("Could not obtain Clover auth token: " + authResult.errorMessage);
        }

        JSONObject payload = new JSONObject();
        payload.put("id", tender.getId());
        payload.put("editable", false);
        payload.put("label", tender.getLabel());
        payload.put("labelKey", tender.getLabelKey());
        payload.put("enabled", tender.getEnabled());
        payload.put("visible", true);
        payload.put("opensCashDrawer", tender.getOpensCashDrawer());
        payload.put("supportsTipping", true);
        payload.put("supportsCashDiscount", false);

        String url = authResult.baseUrl + "/v3/merchants/" + authResult.merchantId
                + "/tenders/" + tender.getId();
        Log.i(TAG, "Updating Clover tender to enable tipping: " + url + " payload=" + payload);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authResult.authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", context.getPackageName() + "/1.0");
        conn.setDoOutput(true);

        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int status = conn.getResponseCode();
        String responseBody = readFully(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
        Log.i(TAG, "Clover tender update response status=" + status + " body=" + responseBody);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Clover tender update failed: HTTP " + status + " " + responseBody);
        }

        ensureMerchantTipsEnabled(authResult);
    }

    private void ensureMerchantTipsEnabled(CloverAuth.AuthResult authResult) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("tipsEnabled", true);

        String url = authResult.baseUrl + "/v3/merchants/" + authResult.merchantId + "/properties";
        Log.i(TAG, "Updating Clover merchant properties to enable tips: " + url + " payload=" + payload);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authResult.authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", context.getPackageName() + "/1.0");
        conn.setDoOutput(true);

        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int status = conn.getResponseCode();
        String responseBody = readFully(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
        Log.i(TAG, "Clover merchant properties update response status=" + status + " body=" + responseBody);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Clover merchant properties update failed: HTTP " + status + " " + responseBody);
        }
    }

    private String readFully(InputStream inputStream) throws Exception {
        if (inputStream == null) return "";
        try (InputStream is = inputStream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = is.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8.name());
        }
    }
}