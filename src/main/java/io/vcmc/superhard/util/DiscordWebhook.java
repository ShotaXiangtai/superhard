package io.vcmc.superhard.util;

import io.vcmc.superhard.SuperHardPlugin;

import javax.net.ssl.HttpsURLConnection;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Discord Webhook に非同期でメッセージを送信する。
 * config.yml の discord.webhook-url が空なら何もしない。
 */
public class DiscordWebhook {

    private final SuperHardPlugin plugin;

    public DiscordWebhook(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    public void send(String message) {
        String url = plugin.getSHConfig().getDiscordWebhookUrl();
        if (url == null || url.isBlank() || url.equals("none")) return;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpsURLConnection conn = (HttpsURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);

                String json = "{\"content\":\"" + escapeJson(message) + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                conn.getResponseCode(); // execute
                conn.disconnect();
            } catch (Exception e) {
                plugin.getLogger().warning("Discord Webhook 送信失敗: " + e.getMessage());
            }
        });
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
