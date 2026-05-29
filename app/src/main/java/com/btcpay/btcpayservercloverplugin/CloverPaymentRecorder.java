package com.buffalodyl.btcpayservercloverplugin;

import android.accounts.Account;
import android.content.Context;
import android.util.Log;

import com.clover.sdk.util.CloverAccount;
import com.clover.sdk.util.CloverAuth;
import com.clover.sdk.v1.tender.Tender;
import com.clover.sdk.v1.tender.TenderConnector;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class CloverPaymentRecorder {
    private static final String TAG = "CloverPaymentRecorder";
    private final Context context;

    public CloverPaymentRecorder(Context context) {
        this.context = context;
    }

    public void recordPayment(String orderId, String employeeId, String externalPaymentId,
                              long baseAmountCents, long tipAmountCents) throws Exception {
        Log.i(TAG, "Recording Clover payment orderId=" + orderId
                + " externalPaymentId=" + externalPaymentId
                + " baseAmountCents=" + baseAmountCents
                + " tipAmountCents=" + tipAmountCents
                + " employeeId=" + employeeId);
        if (orderId == null || orderId.isEmpty()) {
            throw new IllegalArgumentException("Missing Clover order id");
        }
        if (externalPaymentId == null || externalPaymentId.isEmpty()) {
            throw new IllegalArgumentException("Missing external payment id");
        }

        Account account = CloverAccount.getAccount(context);
        if (account == null) {
            throw new IllegalStateException("No Clover account available");
        }

        TenderConnector tenderConnector = new TenderConnector(context, account, null);
        tenderConnector.connect();
        try {
            Tender tender = findTender(tenderConnector.getTenders());
            if (tender == null || tender.getId() == null) {
                throw new IllegalStateException("Could not locate BTCPay tender");
            }

            CloverAuth.AuthResult authResult = CloverAuth.authenticate(context, false, 20L, TimeUnit.SECONDS);
            if (authResult.authToken == null || authResult.baseUrl == null || authResult.merchantId == null) {
                throw new IllegalStateException("Could not obtain Clover auth token: " + authResult.errorMessage);
            }

            JSONObject payload = new JSONObject();
            payload.put("amount", baseAmountCents);
            payload.put("externalPaymentId", externalPaymentId);
            payload.put("tender", new JSONObject().put("id", tender.getId()));
            if (tipAmountCents > 0) {
                payload.put("tipAmount", tipAmountCents);
            }
            if (employeeId != null && !employeeId.isEmpty()) {
                payload.put("employee", new JSONObject().put("id", employeeId));
            }

            String url = authResult.baseUrl + "/v3/merchants/" + authResult.merchantId
                    + "/orders/" + orderId + "/payments";
            Log.i(TAG, "Submitting Clover REST payment to " + url + " payload=" + payload);

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + authResult.authToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }

            int status = conn.getResponseCode();
            String responseBody = readFully(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            Log.i(TAG, "Clover REST payment response status=" + status + " body=" + responseBody);
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Clover REST payment failed: HTTP " + status + " " + responseBody);
            }

            Log.i(TAG, "Clover payment recorded successfully for orderId=" + orderId);
        } finally {
            tenderConnector.disconnect();
        }
    }

    private String readFully(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }
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

    private Tender findTender(List<Tender> tenders) {
        if (tenders == null) {
            return null;
        }
        for (Tender tender : tenders) {
            if (context.getPackageName().equals(tender.getLabelKey())) {
                return tender;
            }
        }
        return null;
    }
}
