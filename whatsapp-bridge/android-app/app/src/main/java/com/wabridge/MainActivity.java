package com.wabridge;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private EditText serverField, tokenField;
    private Button saveButton;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serverField = (EditText) findViewById(R.id.serverField);
        tokenField = (EditText) findViewById(R.id.tokenField);
        saveButton = (Button) findViewById(R.id.saveButton);
        statusText = (TextView) findViewById(R.id.statusText);

        final SharedPreferences prefs = getSharedPreferences("wa_bridge", MODE_PRIVATE);
        String savedServer = prefs.getString("server", "");
        serverField.setText(savedServer);
        tokenField.setText(prefs.getString("token", ""));

        // If we already have saved details, check status right away instead of
        // making the user tap Connect again on every launch (#1, #2).
        if (!savedServer.isEmpty()) {
            statusText.setText("Connecting...");
            new CheckStatusTask().execute(savedServer, prefs.getString("token", ""));
        }

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String server = serverField.getText().toString().trim();
                String token = tokenField.getText().toString().trim();
                if (server.isEmpty()) {
                    statusText.setText("Enter server URL");
                    return;
                }
                if (server.endsWith("/")) {
                    server = server.substring(0, server.length() - 1);
                }
                SharedPreferences.Editor ed = prefs.edit();
                ed.putString("server", server);
                ed.putString("token", token);
                ed.commit();

                statusText.setText("Connecting...");
                new CheckStatusTask().execute(server, token);
            }
        });
    }

    private class CheckStatusTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... params) {
            String server = params[0];
            String token = params[1];
            try {
                URL url = new URL(server + "/status");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("X-Device-Token", token);
                int code = conn.getResponseCode();
                if (code != 200) return "Server error: " + code;
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                JSONObject json = new JSONObject(sb.toString());
                String state = json.getString("state");
                String user = json.optString("user", "");
                if ("ready".equals(state)) {
                    return "Connected! Logged in as " + user;
                } else if ("qr".equals(state)) {
                    return "QR code waiting. Open " + server + "/qr/page in a browser to scan.";
                } else {
                    return "Server state: " + state;
                }
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }

        @Override
        protected void onPostExecute(String result) {
            statusText.setText(result);
            if (result.startsWith("Connected!")) {
                startService(new Intent(MainActivity.this, WaSyncService.class));
                Intent intent = new Intent(MainActivity.this, ChatListActivity.class);
                startActivity(intent);
                finish();
            }
        }
    }
}
