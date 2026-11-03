package com.renatovpn;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.wireguard.android.backend.Backend;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private Backend backend;
    private Config config;
    private AppTunnel tunnel;
    private TextView statusText;
    private TextView serverText;
    private Button connectButton;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> filePicker =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onConfigSelected);

    private final ActivityResultLauncher<Intent> vpnPermission =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) connectNow();
            else showMessage("A autorização VPN é necessária para conectar.");
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        backend = new GoBackend(this);
        buildScreen();
    }

    private void buildScreen() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(36, 56, 36, 36);
        page.setBackgroundColor(0xFF101827);

        TextView title = new TextView(this);
        title.setText("RENATO VPN");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, 1);
        page.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("PRIVACIDADE • SEGURANÇA • LIBERDADE");
        subtitle.setTextColor(0xFF9FB0C5);
        subtitle.setTextSize(12);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 12, 0, 48);
        page.addView(subtitle, matchWrap());

        statusText = new TextView(this);
        statusText.setText("DESCONECTADO");
        statusText.setTextColor(0xFFFFC857);
        statusText.setTextSize(18);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 16, 0, 16);
        page.addView(statusText, matchWrap());

        serverText = new TextView(this);
        serverText.setText("Nenhum servidor importado");
        serverText.setTextColor(0xFFD5DFEA);
        serverText.setTextSize(14);
        serverText.setGravity(Gravity.CENTER);
        serverText.setPadding(0, 0, 0, 28);
        page.addView(serverText, matchWrap());

        Button importButton = new Button(this);
        importButton.setText("IMPORTAR CONFIGURAÇÃO .CONF");
        importButton.setOnClickListener(v -> filePicker.launch(new String[]{"*/*"}));
        page.addView(importButton, matchWidth());

        connectButton = new Button(this);
        connectButton.setText("CONECTAR");
        connectButton.setEnabled(false);
        connectButton.setOnClickListener(v -> requestOrDisconnect());
        LinearLayout.LayoutParams connectParams = matchWidth();
        connectParams.topMargin = 18;
        page.addView(connectButton, connectParams);

        TextView note = new TextView(this);
        note.setText("Importe um arquivo WireGuard .conf fornecido por seu provedor VPN.");
        note.setTextColor(0xFF9FB0C5);
        note.setTextSize(12);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, 34, 0, 0);
        page.addView(note, matchWrap());

        setContentView(page);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private void onConfigSelected(Uri uri) {
        if (uri == null) return;
        statusText.setText("LENDO CONFIGURAÇÃO...");
        executor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("Não foi possível abrir o arquivo.");
                Config parsed = Config.parse(input);
                runOnUiThread(() -> {
                    config = parsed;
                    tunnel = new AppTunnel("renato-vpn");
                    serverText.setText("Configuração WireGuard carregada");
                    statusText.setText("PRONTO PARA CONECTAR");
                    connectButton.setText("CONECTAR");
                    connectButton.setEnabled(true);
                    showMessage("Configuração importada com sucesso.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("ERRO NA CONFIGURAÇÃO");
                    showMessage("Arquivo inválido: " + e.getMessage());
                });
            }
        });
    }

    private void requestOrDisconnect() {
        if (config == null || tunnel == null) return;
        if (isConnected) {
            executor.execute(() -> {
                try {
                    backend.setState(tunnel, Tunnel.State.DOWN, null);
                    isConnected = false;
                    runOnUiThread(() -> {
                        statusText.setText("DESCONECTADO");
                        connectButton.setText("CONECTAR");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> showMessage("Erro ao desconectar: " + e.getMessage()));
                }
            });
            return;
        }

        Intent intent = GoBackend.VpnService.prepare(this);
        if (intent != null) vpnPermission.launch(intent);
        else connectNow();
    }

    private volatile boolean isConnected = false;

    private void connectNow() {
        statusText.setText("CONECTANDO...");
        connectButton.setEnabled(false);
        executor.execute(() -> {
            try {
                backend.setState(tunnel, Tunnel.State.UP, config);
                isConnected = true;
                runOnUiThread(() -> {
                    statusText.setText("TÚNEL ATIVADO");
                    connectButton.setText("DESCONECTAR");
                    connectButton.setEnabled(true);
                });
            } catch (Exception e) {
                isConnected = false;
                runOnUiThread(() -> {
                    statusText.setText("FALHA NA CONEXÃO");
                    connectButton.setText("CONECTAR");
                    connectButton.setEnabled(true);
                    showMessage("Não foi possível conectar: " + e.getMessage());
                });
            }
        });
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private static final class AppTunnel implements Tunnel {
        private final String name;
        AppTunnel(String name) { this.name = name; }
        @Override public String getName() { return name; }
        @Override public void onStateChange(State newState) { }
    }
}
