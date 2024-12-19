package com.example.plugin.manager.managerplugin;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class ManagerPlugin extends JavaPlugin implements Listener {

    static Queue<String> chatLogQueue = new ConcurrentLinkedQueue<>();



    @Override
    public void onEnable() {

        getCommand("say").setExecutor(this);
        // Register the event listener
        Bukkit.getPluginManager().registerEvents(this, this);

        // Start a new thread for text message communication
        ChatHttpServer chatHttpServer = new ChatHttpServer(12345);
        chatHttpServer.start();

        CommandSocketThread commandSocketThread = new CommandSocketThread(12346, this);
        commandSocketThread.start();

        new JsonHttpServer().start();

        getCommand("gps").setExecutor(new GPSCommand());
        getCommand("say").setExecutor(new SayCommand());
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        // チャットが送信されたらメモリに保存
        String chatMessage = "[" + event.getPlayer().getName() + "] " + event.getMessage();
        chatLogQueue.add(chatMessage);
    }

    public class SayCommand implements CommandExecutor {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command send a message.");
                return true;
            }

            Player player = (Player) sender;

            if (args.length != 1) {
                player.sendMessage("Usage: /say <message>");
                return true;
            }

            String message = args[0];

            message = "[" + player.getName() + "] " + message;

            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(message);
            }

            chatLogQueue.add(message);

            return true;
        }
    }


    public class GPSCommand implements CommandExecutor {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be executed by a player.");
                return true;
            }

            Player player = (Player) sender;

            if (args.length != 1) {
                player.sendMessage("Usage: /gps <player>");
                return true;
            }

            String targetPlayerName = args[0];
            Player targetPlayer = Bukkit.getPlayer(targetPlayerName);

            if (targetPlayer == null) {
                player.sendMessage("Player not found: " + targetPlayerName);
                return true;
            }

            double x = targetPlayer.getLocation().getX();
            double y = targetPlayer.getLocation().getY();
            double z = targetPlayer.getLocation().getZ();

            player.sendMessage("(X, Y, Z) = (" + (int)x + ", " + (int)y + ", " + (int)z + ")");

            return true;
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        event.getPlayer().sendMessage("********* Current Server Info *********");
        event.getPlayer().sendMessage("Players: " + Bukkit.getOnlinePlayers().toArray().length + " people");

        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            count++;
            event.getPlayer().sendMessage("   " + count + " --- " + player.getName());
        }
        event.getPlayer().sendMessage("***************************************");
    }

    public static class ChatHttpServer {

        private int port;

        public ChatHttpServer(int port) {
            this.port = port;
        }

        public void start() {
            new SocketThread(port).start();
        }

        private static class SocketThread extends Thread {
            private int port;

            public SocketThread(int port) {
                this.port = port;
            }

            @Override
            public void run() {
                try (ServerSocket serverSocket = new ServerSocket(port)) {
                    while (true) {
                        Socket clientSocket = serverSocket.accept();
                        handleClientSocket(clientSocket);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            private void handleClientSocket(Socket clientSocket) {
                ExecutorService executorService = Executors.newSingleThreadExecutor();

                executorService.submit(() -> {
                    try {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                        StringBuilder receivedMessage = new StringBuilder();

                        String line;
                        while ((line = reader.readLine()) != null) {
                            receivedMessage.append(line);
                        }

                        for (Player player : Bukkit.getOnlinePlayers()) {
                            player.sendMessage(receivedMessage.toString());
                        }

                        String chatMessage = "[Server] " + receivedMessage.toString();
                        chatLogQueue.add(chatMessage);

                        clientSocket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }
        }
    }

    public static class CommandSocketThread {

        private int port;
        private JavaPlugin plugin;

        public CommandSocketThread(int port, JavaPlugin plugin) {
            this.port = port;
            this.plugin = plugin;
        }

        public void start() {
            new SocketThread(port, plugin).start();
        }

        private static class SocketThread extends Thread {

            private int port;
            private JavaPlugin plugin;

            public SocketThread(int port, JavaPlugin plugin) {
                this.port = port;
                this.plugin = plugin;
            }

            @Override
            public void run() {
                try (ServerSocket serverSocket = new ServerSocket(port)) {
                    while (true) {
                        Socket clientSocket = serverSocket.accept();
                        handleClientSocket(clientSocket);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            private void handleClientSocket(Socket clientSocket) {
                ExecutorService executorService = Executors.newSingleThreadExecutor();

                executorService.submit(() -> {
                    try {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                        StringBuilder receivedCommand = new StringBuilder();

                        String line;
                        while ((line = reader.readLine()) != null) {
                            receivedCommand.append(line);
                        }

                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), receivedCommand.toString());
                        });

                        clientSocket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }
        }
    }

    public static class JsonHttpServer {

        public void start() {
            int port = 12347;
            HttpServer server = null;
            try {
                server = HttpServer.create(new InetSocketAddress(port), 0);
                server.createContext("/get-json", new JsonHandler());
                server.createContext("/chat-log", new ChatLogJsonHandler(chatLogQueue));

                server.createContext("/", (exchange -> {
                    Headers headers = exchange.getResponseHeaders();
                    headers.add("Access-Control-Allow-Origin", "*");
                    headers.add("Access-Control-Allow-Methods", "GET, OPTIONS");
                    headers.add("Access-Control-Allow-Headers", "Content-Type");
                    if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                }));

                server.setExecutor(null);
                server.start();
                System.out.println("Server is running on port " + port);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        static class JsonHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange t) throws IOException {
                JSONObject jsonData = new JSONObject();
                jsonData.put("nop", Bukkit.getOnlinePlayers().toArray().length);

                List<String> playersList = new ArrayList<>();

                for (Player player : Bukkit.getOnlinePlayers()) {
                    playersList.add(player.getName());
                }

                jsonData.put("people", String.join(", ", playersList));
                String response = jsonData.toJSONString();

                Headers headers = t.getResponseHeaders();
                headers.add("Access-Control-Allow-Origin", "*");
                headers.add("Access-Control-Allow-Methods", "GET, OPTIONS");
                headers.add("Access-Control-Allow-Headers", "Content-Type");

                t.getResponseHeaders().set("Content-Type", "application/json");
                t.sendResponseHeaders(200, response.length());

                OutputStream os = t.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        }

        static class ChatLogJsonHandler implements HttpHandler {

            private Queue<String> chatLogQueue;

            public ChatLogJsonHandler(Queue<String> chatLogQueue) {
                this.chatLogQueue = chatLogQueue;
            }

            @Override
            public void handle(HttpExchange t) throws IOException {

                JSONArray chatLogArray = new JSONArray();

                int count = 0;
                List<String> chatLogList = new ArrayList<>(chatLogQueue);
                int size = chatLogList.size();

                for (int i = size - 10; i < size; i++) {
                    if (i >= 0) {
                        String message = chatLogList.get(i);
                        chatLogArray.add(message);
                        count++;
                    }
                }

                String response = chatLogArray.toJSONString();

                Headers headers = t.getResponseHeaders();
                headers.add("Access-Control-Allow-Origin", "*");
                headers.add("Access-Control-Allow-Methods", "GET, OPTIONS");
                headers.add("Access-Control-Allow-Headers", "Content-Type");

                t.getResponseHeaders().set("Content-Type", "application/json");
                t.sendResponseHeaders(200, response.length());

                OutputStream os = t.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        }
    }


}


