package com.example.localsensorcloud;

import android.content.Context;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

final class PairingClient {
    interface Listener {
        void onVerificationCode(String code);
        void onApproved();
        void onFailure(String message);
    }

    private static final long TIMEOUT_MILLIS = 5 * 60 * 1000L;
    private static final long POLL_MILLIS = 1500L;

    private PairingClient() { }

    static void pair(Context context, String endpointValue, String deviceId, String deviceName, Listener listener) {
        try {
            String endpoint = PairingStore.normalizeEndpoint(endpointValue);
            if (!endpoint.startsWith("https://")) throw new IOException("The laptop address must begin with https://");
            SSLSocketFactory pairingSocketFactory = createPairingSocketFactory();
            byte[] nonce = new byte[32];
            new SecureRandom().nextBytes(nonce);
            String nonceBase64 = Base64.encodeToString(nonce, Base64.NO_WRAP);

            JSONObject requestBody = new JSONObject()
                    .put("deviceId", deviceId)
                    .put("deviceName", deviceName)
                    .put("clientNonce", nonceBase64);
            Response request = call(pairingSocketFactory, endpoint + "/api/pair/request", "POST",
                    requestBody.toString().getBytes(StandardCharsets.UTF_8), null);
            ensureSuccess(request);
            JSONObject requestPayload = new JSONObject(request.body);
            String requestId = requestPayload.getString("requestId");
            byte[] observedCertificate = request.peerCertificate;
            listener.onVerificationCode(verificationCode(observedCertificate, nonce, requestId));

            String query = "?requestId=" + encode(requestId)
                    + "&deviceId=" + encode(deviceId)
                    + "&clientNonce=" + encode(nonceBase64);
            long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Pairing was cancelled");
                Thread.sleep(POLL_MILLIS);
                Response status = call(pairingSocketFactory, endpoint + "/api/pair/status" + query,
                        "GET", null, observedCertificate);
                ensureSuccess(status);
                JSONObject statusPayload = new JSONObject(status.body);
                String state = statusPayload.optString("status", "pending");
                if ("denied".equals(state)) throw new IOException("The laptop denied this connection request");
                if (!"approved".equals(state)) continue;

                byte[] applicationKey = Base64.decode(statusPayload.getString("applicationKey"), Base64.NO_WRAP);
                PairingStore.save(context, endpoint, deviceId, observedCertificate, applicationKey);
                listener.onApproved();
                return;
            }
            throw new IOException("Pairing expired. Send a new request and approve it within five minutes.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            listener.onFailure("Pairing was cancelled");
        } catch (IOException | GeneralSecurityException | JSONException | IllegalArgumentException error) {
            String message = error.getMessage();
            listener.onFailure(message == null || message.trim().isEmpty() ? "Could not pair with the laptop" : message);
        }
    }

    private static Response call(SSLSocketFactory socketFactory, String targetValue, String method,
                                 byte[] body, byte[] expectedCertificate)
            throws IOException, GeneralSecurityException {
        URL target = new URL(targetValue);
        if (!"https".equalsIgnoreCase(target.getProtocol())) throw new IOException("Unencrypted HTTP pairing is disabled");
        HttpsURLConnection connection = (HttpsURLConnection) target.openConnection();
        try {
            connection.setSSLSocketFactory(socketFactory);
            connection.setHostnameVerifier((hostname, session) -> true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "LocalSensorCloud-Android");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            int status = connection.getResponseCode();
            Certificate[] certificates = connection.getServerCertificates();
            if (certificates.length == 0) throw new IOException("The laptop did not provide a TLS certificate");
            byte[] peerCertificate = certificates[0].getEncoded();
            if (expectedCertificate != null && !MessageDigest.isEqual(expectedCertificate, peerCertificate)) {
                throw new GeneralSecurityException("The laptop certificate changed during pairing");
            }
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, readSmallResponse(stream), peerCertificate);
        } finally {
            connection.disconnect();
        }
    }

    private static void ensureSuccess(Response response) throws IOException {
        if (response.status >= 200 && response.status < 300) return;
        try {
            String error = new JSONObject(response.body).optString("error", "");
            throw new IOException(error.isEmpty() ? "Laptop returned " + response.status : error);
        } catch (JSONException ignored) {
            throw new IOException("Laptop returned " + response.status);
        }
    }

    private static SSLSocketFactory createPairingSocketFactory() throws GeneralSecurityException {
        X509TrustManager temporaryTrust = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new X509TrustManager[] { temporaryTrust }, new SecureRandom());
        return context.getSocketFactory();
    }

    private static String verificationCode(byte[] certificate, byte[] nonce, String requestId)
            throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(certificate);
        digest.update(nonce);
        digest.update(requestId.getBytes(StandardCharsets.UTF_8));
        byte[] value = digest.digest();
        long firstFourBytes = ((long) (value[0] & 0xff) << 24)
                | ((long) (value[1] & 0xff) << 16)
                | ((long) (value[2] & 0xff) << 8)
                | (value[3] & 0xffL);
        return String.format(Locale.US, "%06d", firstFourBytes % 1_000_000L);
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static String readSmallResponse(InputStream input) throws IOException {
        if (input == null) return "";
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0 && output.size() < 64 * 1024) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static final class Response {
        final String body;
        final byte[] peerCertificate;
        final int status;

        Response(int status, String body, byte[] peerCertificate) {
            this.status = status;
            this.body = body;
            this.peerCertificate = peerCertificate;
        }
    }
}
