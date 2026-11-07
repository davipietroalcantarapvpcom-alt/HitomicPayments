package com.hitomic.payments;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;

public final class HitomicPayments extends JavaPlugin {
    private HttpServer httpServer;
    private LuckPerms luckPerms;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        RegisteredServiceProvider<LuckPerms> provider =
                getServer().getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) {
            getLogger().severe("LuckPerms não encontrado. Desativando plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        luckPerms = provider.getProvider();
        startWebhookIfEnabled();
        getLogger().info("HitomicPayments iniciado. Webhook habilitado: " +
                getConfig().getBoolean("webhook.enabled"));
    }

    @Override
    public void onDisable() {
        stopWebhook();
    }

    private void startWebhookIfEnabled() {
        if (!getConfig().getBoolean("webhook.enabled")) return;
        String token = getConfig().getString("webhook.token", "");
        if (token.length() < 32 || token.startsWith("TROQUE_")) {
            getLogger().severe("Configure um token com pelo menos 32 caracteres antes de habilitar o webhook.");
            return;
        }
        try {
            String address = getConfig().getString("webhook.bind-address", "127.0.0.1");
            int port = getConfig().getInt("webhook.port", 27512);
            String path = getConfig().getString("webhook.path", "/webhook");
            httpServer = HttpServer.create(new InetSocketAddress(address, port), 0);
            httpServer.createContext(path, this::handleRequest);
            httpServer.setExecutor(Executors.newFixedThreadPool(2));
            httpServer.start();
            getLogger().info("Webhook ouvindo em " + address + ":" + port + path);
        } catch (IOException ex) {
            getLogger().severe("Não foi possível iniciar o webhook: " + ex.getMessage());
        }
    }

    private void stopWebhook() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "Method Not Allowed");
            return;
        }
        String expected = getConfig().getString("webhook.token", "");
        String supplied = exchange.getRequestHeaders().getFirst("X-Hitomic-Token");
        if (supplied == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            respond(exchange, 401, "Unauthorized");
            return;
        }

        int max = getConfig().getInt("webhook.max-body-bytes", 16384);
        byte[] body = exchange.getRequestBody().readNBytes(max + 1);
        if (body.length > max) {
            respond(exchange, 413, "Payload Too Large");
            return;
        }

        try {
            JsonObject json = JsonParser.parseString(
                    new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            String orderId = value(json, "order_id");
            String nick = value(json, "nick");
            String product = value(json, "product");
            String status = value(json, "status").toLowerCase(Locale.ROOT);

            if (orderId.isBlank() || !nick.matches("[A-Za-z0-9_]{3,16}") ||
                    product.isBlank()) {
                respond(exchange, 400, "Invalid order data");
                return;
            }
            List<String> paid = getConfig().getStringList("paid-statuses");
            if (paid.stream().noneMatch(s -> s.equalsIgnoreCase(status))) {
                respond(exchange, 202, "Ignored: order is not paid");
                return;
            }

            String group = findGroup(product);
            if (group == null) {
                respond(exchange, 400, "Unknown product");
                return;
            }

            respond(exchange, 202, "Accepted");
            Bukkit.getScheduler().runTask(this, () -> grantGroup(orderId, nick, group));
        } catch (JsonSyntaxException | IllegalStateException ex) {
            respond(exchange, 400, "Invalid JSON");
        }
    }

    private String findGroup(String product) {
        String normalized = product.toLowerCase(Locale.ROOT).trim();
        for (String group : List.of("vipplus", "mvp", "lord", "hitomic")) {
            for (String alias : getConfig().getStringList("products." + group)) {
                if (normalized.equals(alias.toLowerCase(Locale.ROOT).trim())) return group;
            }
        }
        return null;
    }

    private void grantGroup(String orderId, String nick, String group) {
        UUID uuid = Bukkit.getOfflinePlayer(nick).getUniqueId();
        luckPerms.getUserManager().loadUser(uuid).thenAccept(user -> {
            InheritanceNode node = InheritanceNode.builder(group).build();
            user.data().add(node);
            luckPerms.getUserManager().saveUser(user).thenRun(() ->
                    getLogger().info("Pedido " + orderId + ": grupo " + group +
                            " aplicado a " + nick + "."));
        }).exceptionally(error -> {
            getLogger().severe("Falha ao entregar pedido " + orderId + ": " + error.getMessage());
            return null;
        });
    }

    private String value(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull()
                ? json.get(key).getAsString() : "";
    }

    private void respond(HttpExchange exchange, int code, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            stopWebhook();
            startWebhookIfEnabled();
            sender.sendMessage("§aConfiguração do HitomicPayments recarregada.");
            return true;
        }
        sender.sendMessage("§eUse: /hitomicpayments reload");
        return true;
    }
}
