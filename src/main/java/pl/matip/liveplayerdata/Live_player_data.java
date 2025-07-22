package pl.matip.liveplayerdata;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
// Import needed for the JOIN event handler signature
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.fabricmc.fabric.api.networking.v1.PacketSender;

import org.java_websocket.server.WebSocketServer;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.WebSocket;

import java.net.InetSocketAddress;
import java.util.*;


import java.io.Console;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

// Bitmask definitions
// bit 0: coordinates changed
// bit 1: health changed
// bit 2: xp changed
// bit 3: achievements changed (stub)
public class Live_player_data implements DedicatedServerModInitializer {

    // --- Message Type Constants ---
    private static final byte MSG_TYPE_JOIN = 0x01;
    private static final byte MSG_TYPE_LEAVE = 0x02;
    private static final byte MSG_TYPE_UPDATE = 0x03;
    private static final byte MSG_TYPE_NEW_ACHIEVEMENT = 0x04;

    // --- Change Mask Bits (for UPDATE message) ---
    private static final byte MASK_COORDS = 0x01;
    private static final byte MASK_HEALTH = 0x02;
    private static final byte MASK_XP = 0x04;

    // --- WebSocket Server ---
    private MyWebSocketServer wsServer;
    private int wsPort = 8887;
    private final Set<WebSocket> connectedClients = Collections.synchronizedSet(new HashSet<>());

    // --- Player Data Tracking ---
    private HashMap<String, PlayerData> playerDataMap;
    private int tickDebouncer;

    // --- Stored Server Instance ---
    private MinecraftServer storedServer = null; // Field to store the server instance

    // --- Static Instance ---
    private static Live_player_data instance;

    public Live_player_data() {
        instance = this;
    }

    public static Live_player_data getInstance() {
        return instance;
    }

    // --- Getter for the stored server instance ---
    public MinecraftServer getStoredServerInstance() {
        return this.storedServer;
    }

    @Override
    public void onInitializeServer() {
        System.out.println("Initializing Live Player data (WebSocket Mode)...");
        playerDataMap = new HashMap<>();
        tickDebouncer = 0;
        instance = this;

        // --- Initialize WebSocket Server ---
        try {
            InetSocketAddress address = new InetSocketAddress(wsPort);
            wsServer = new MyWebSocketServer(address, this); // Pass instance
            new Thread(wsServer::start).start();
            System.out.println("WebSocket server started on port: " + wsPort);
        } catch (Exception e) { /* ... error handling ... */ }

        // --- Register Minecraft Events ---
        ServerTickEvents.END_SERVER_TICK.register(this::handleServerTick);
        ServerPlayConnectionEvents.JOIN.register(this::handlePlayerJoin);
        ServerPlayConnectionEvents.DISCONNECT.register(this::handlePlayerLeave);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::handleServerStopping);
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted); // Register the handler
        System.out.println("Live Player data initialized.");
    }

    // --- Event Handler for Server Started ---
    private void onServerStarted(MinecraftServer server) { // This method is fine
        System.out.println("Server started! Storing server instance.");
        this.storedServer = server;
    }

    private Set<String> getPlayerAdvancements(ServerPlayerEntity player) {
        // ... (implementation remains the same) ...
        Set<String> completed = new HashSet<>();
        if (player.getServer() != null) {
            for (AdvancementEntry entry : player.getServer().getAdvancementLoader().getAdvancements()) {
                AdvancementProgress progress = player.getAdvancementTracker().getProgress(entry);
                if (progress.isDone()) {
                    completed.add(entry.id().toString());
                }
            }
        }
        return completed;
    }

    // --- Event Handlers ---

    private void handleServerStopping(MinecraftServer server) {
        System.out.println("Stopping WebSocket server...");
        if (wsServer != null) {
            try {
                // Stop accepting new connections and close existing ones
                wsServer.stop(1000); // Timeout in milliseconds
                System.out.println("WebSocket server stopped.");
            } catch (InterruptedException e) {
                System.err.println("Error stopping WebSocket server:");
                e.printStackTrace();
                Thread.currentThread().interrupt(); // Re-interrupt thread
            }
        }
    }

    private void handlePlayerJoin(ServerPlayNetworkHandler handler, PacketSender sender, MinecraftServer server) {
        ServerPlayerEntity player = handler.player;
        String playerName = player.getGameProfile().getName();
        System.out.println("Player joined: " + playerName + ". Sending initial data via WebSocket.");

        PlayerData currentData = new PlayerData(
                player.getX(), player.getY(), player.getZ(),
                player.getHealth(), player.experienceLevel, "dummy_on_join"
        );
        playerDataMap.put(playerName, currentData);

        try {
            byte[] joinMessage = serializeJoinMessage(player); // Use the helper
            broadcastBinary(joinMessage); // Send binary data
        } catch (Exception e) {
            System.err.println("Error serializing join message for " + playerName);
            e.printStackTrace();
        }
    }

    private void handlePlayerLeave(ServerPlayNetworkHandler handler, MinecraftServer server) {
        String playerName = handler.player.getGameProfile().getName();
        System.out.println("Player left: " + playerName + ". Sending leave notification via WebSocket (Binary).");
        playerDataMap.remove(playerName);

        // --- Serialize LEAVE message ---
        try {
            byte[] nameBytes = playerName.getBytes(StandardCharsets.UTF_8);
            int capacity = 1 // Type
                    + 1 + nameBytes.length; // Name
            ByteBuffer buffer = ByteBuffer.allocate(capacity);
            buffer.put(MSG_TYPE_LEAVE);
            buffer.put((byte) nameBytes.length);
            buffer.put(nameBytes);
            broadcastBinary(buffer.array());
        } catch (Exception e) {
            System.err.println("Error serializing leave message for " + playerName);
            e.printStackTrace();
        }
    }

    private void handleServerTick(MinecraftServer server) {
        if (tickDebouncer < 30) {
            tickDebouncer++;
            return;
        }
        tickDebouncer = 0;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String playerName = player.getGameProfile().getName();
            PlayerData oldData = playerDataMap.get(playerName);

            if (oldData == null) {
                // Player joined between events - JOIN message should handle this via onOpen now.
                // If still needed, trigger a full JOIN serialization here.
                System.err.println("Warning: Player " + playerName + " found in tick but not in map. Should be handled by onOpen.");
                // Optionally force a JOIN message send here if onOpen proves unreliable
                continue;
            }

            // Get current stats
            double currentX = player.getX();
            double currentY = player.getY();
            double currentZ = player.getZ();
            float currentHealth = player.getHealth();
            int currentXp = player.experienceLevel;

            byte changeMask = 0;
            // Compare basic fields
            if (oldData.x != currentX || oldData.y != currentY || oldData.z != currentZ) {
                changeMask |= MASK_COORDS;
            }
            if (oldData.health != currentHealth) {
                changeMask |= MASK_HEALTH;
            }
            if (oldData.xp != currentXp) {
                changeMask |= MASK_XP;
            }
            if (changeMask != 0) {
                // --- Serialize UPDATE message ---
                try {
                    byte[] nameBytes = playerName.getBytes(StandardCharsets.UTF_8);
                    int dataCapacity = 0;
                    if ((changeMask & MASK_COORDS) != 0) dataCapacity += 24; // 3 * double
                    if ((changeMask & MASK_HEALTH) != 0) dataCapacity += 4;  // float
                    if ((changeMask & MASK_XP) != 0) dataCapacity += 4;  // int

                    int capacity = 1 // Type
                            + 1 + nameBytes.length // Name
                            + 1 // Mask
                            + dataCapacity; // Conditional data

                    ByteBuffer buffer = ByteBuffer.allocate(capacity);
                    buffer.put(MSG_TYPE_UPDATE);
                    buffer.put((byte) nameBytes.length);
                    buffer.put(nameBytes);
                    buffer.put(changeMask);

                    // Add data based on mask
                    if ((changeMask & MASK_COORDS) != 0) {
                        buffer.putDouble(currentX);
                        buffer.putDouble(currentY);
                        buffer.putDouble(currentZ);
                        oldData.x = currentX;
                        oldData.y = currentY;
                        oldData.z = currentZ; // Update stored
                    }
                    if ((changeMask & MASK_HEALTH) != 0) {
                        buffer.putFloat(currentHealth);
                        oldData.health = currentHealth; // Update stored
                    }
                    if ((changeMask & MASK_XP) != 0) {
                        buffer.putInt(currentXp);
                        oldData.xp = currentXp; // Update stored
                    }

                    broadcastBinary(buffer.array());

                } catch (Exception e) {
                    System.err.println("Error serializing update message for " + playerName);
                    e.printStackTrace();
                }
            }
        }
    }

    // --- Method to broadcast BINARY message ---
    private void broadcastBinary(byte[] message) {
        if (wsServer == null) return;
        synchronized (connectedClients) {
            if (connectedClients.isEmpty()) return;
            // System.out.println("Broadcasting binary: " + message.length + " bytes");
            for (WebSocket client : connectedClients) {
                if (client.isOpen()) {
                    client.send(message); // Send byte array
                }
            }
        }
    }

    // --- Method for Mixin to trigger single achievement update ---
    public void sendSingleAchievementUpdate(String playerName, String achievementId) {
        System.out.println("Sending single achievement update via WebSocket (Binary) for " + playerName + ": " + achievementId);

        // --- Serialize NEW_ACHIEVEMENT message ---
        try {
            byte[] nameBytes = playerName.getBytes(StandardCharsets.UTF_8);
            byte[] achBytes = achievementId.getBytes(StandardCharsets.UTF_8);
            if (achBytes.length > 255) { // Check length limit
                System.err.println("Achievement ID too long to send: " + achievementId);
                return;
            }

            int capacity = 1 // Type
                    + 1 + nameBytes.length // Name
                    + 1 + achBytes.length; // Achievement ID

            ByteBuffer buffer = ByteBuffer.allocate(capacity);
            buffer.put(MSG_TYPE_NEW_ACHIEVEMENT);
            buffer.put((byte) nameBytes.length);
            buffer.put(nameBytes);
            buffer.put((byte) achBytes.length);
            buffer.put(achBytes);

            broadcastBinary(buffer.array());

        } catch (Exception e) {
            System.err.println("Error serializing achievement message for " + playerName);
            e.printStackTrace();
        }
    }



//    private void sendUdpLeaveNotification(String playerName) {
//        if (udpSocket == null || remoteAddress == null) {
//            System.err.println("UDP Socket not initialized. Cannot send leave notification for " + playerName);
//            return;
//        }
//        try {
//            byte[] playerNameBytes = playerName.getBytes(StandardCharsets.UTF_8);
//
//            // Capacity: Magic (2) + NameLen (1) + NameBytes + Mask (1)
//            // NO data fields are included for a leave message.
//            int capacity = 2 + 1 + playerNameBytes.length + 1;
//
//            ByteBuffer buffer = ByteBuffer.allocate(capacity);
//            buffer.put((byte) 0xCA);
//            buffer.put((byte) 0xFE);
//            buffer.put((byte) playerNameBytes.length);
//            buffer.put(playerNameBytes);
//            buffer.put(LEAVE_MASK); // Put the specific leave mask
//
//            byte[] packetData = buffer.array();
//            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, remoteAddress, remotePort);
//            udpSocket.send(packet);
//            // System.out.println("Sent UDP leave notification for " + playerName);
//
//        } catch (Exception e) {
//            System.err.println("Failed to send UDP leave notification for " + playerName + ":");
//            e.printStackTrace();
//        }
//    }
//
//    private void sendUdpUpdate(String playerName, byte changeMask, PlayerData data) {
//        // Make sure socket is initialized before trying to send
//        if (udpSocket == null || remoteAddress == null) {
//            System.err.println("UDP Socket not initialized. Cannot send update for " + playerName);
//            return;
//        }
//        try {
//            byte[] playerNameBytes = playerName.getBytes(StandardCharsets.UTF_8);
//            byte[] achievementsBytes = data.achievements.getBytes(StandardCharsets.UTF_8);
//
//            int capacity = 2 + 1 + playerNameBytes.length + 1; // Magic + nameLen + name + changeMask
//            if ((changeMask & 0x1) != 0) capacity += 24; // Coords
//            if ((changeMask & 0x2) != 0) capacity += 4;  // Health
//            if ((changeMask & 0x4) != 0) capacity += 4;  // XP
//            if ((changeMask & 0x8) != 0) capacity += 1 + achievementsBytes.length; // Achievements Len + Data
//
//            ByteBuffer buffer = ByteBuffer.allocate(capacity);
//            buffer.put((byte) 0xCA);
//            buffer.put((byte) 0xFE);
//            buffer.put((byte) playerNameBytes.length);
//            buffer.put(playerNameBytes);
//            buffer.put(changeMask);
//
//            if ((changeMask & 0x1) != 0) {
//                buffer.putDouble(data.x);
//                buffer.putDouble(data.y);
//                buffer.putDouble(data.z);
//            }
//            if ((changeMask & 0x2) != 0) {
//                buffer.putFloat(data.health);
//            }
//            if ((changeMask & 0x4) != 0) {
//                buffer.putInt(data.xp);
//            }
//            if ((changeMask & 0x8) != 0) {
//                buffer.put((byte) achievementsBytes.length);
//                buffer.put(achievementsBytes);
//            }
//
//            byte[] packetData = buffer.array();
//            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, remoteAddress, remotePort);
//            udpSocket.send(packet);
//            // Optional: Log the send
//            // System.out.println("Sent UDP update for " + playerName + " mask: " + String.format("0x%02X", changeMask));
//
//        } catch (Exception e) {
//            System.err.println("Failed to send UDP update for " + playerName + ":");
//            e.printStackTrace();
//        }
//    }

    // --- PlayerData Class (Unchanged) ---
    private static class PlayerData {
        double x, y, z;
        float health;
        int xp;
        String achievements;

        PlayerData(double x, double y, double z, float health, int xp, String achievements) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.health = health;
            this.xp = xp;
            this.achievements = achievements;
        }

        boolean coordsEquals(PlayerData other) {
            return this.x == other.x && this.y == other.y && this.z == other.z;
        }
    }

    // Helper to get current data for a player (needed by Mixin)
    public PlayerData getCurrentPlayerData(ServerPlayerEntity player) {
        // This might just return the cached data, or fetch fresh if needed
        // Be careful about thread safety if fetching fresh data outside server thread
        return playerDataMap.get(player.getGameProfile().getName());
        // Or fetch fresh:
        // return new PlayerData(player.getX(), player.getY(), player.getZ(),
        //                      player.getHealth(), player.experienceLevel,
        //                      "fetch_real_advancements_here");
    }

    // --- Inner class for the WebSocket Server implementation ---
    private class MyWebSocketServer extends WebSocketServer {
        private final Live_player_data modInstance;

        public MyWebSocketServer(InetSocketAddress address, Live_player_data instance) {
            super(address);
            this.modInstance = instance;
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            modInstance.connectedClients.add(conn);
            System.out.println("WebSocket connection opened: " + conn.getRemoteSocketAddress());

            // Send current state of all online players to the newly connected client (BINARY)
            // Use the stored server instance
            MinecraftServer server = modInstance.getStoredServerInstance();
            if (server != null) {
                server.execute(() -> {
                    System.out.println("Sending initial player states (Binary) to new client: " + conn.getRemoteSocketAddress());
                    for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                        try {
                            byte[] joinMessage = modInstance.serializeJoinMessage(player); // Use helper via modInstance

                            if (conn.isOpen()) {
                                conn.send(joinMessage); // Send to the specific new client
                            } else {
                                System.out.println("WebSocket connection closed before sending initial state for " + player.getGameProfile().getName());
                                break; // Stop sending to this client
                            }
                        } catch (Exception e) {
                            System.err.println("Error serializing initial state for player " + player.getGameProfile().getName() + " for new client");
                            e.printStackTrace();
                        }
                    }
                    System.out.println("Finished sending initial states (Binary) to new client.");
                });
            } else {
                System.err.println("Cannot send initial state: Server instance not available yet.");
            }
        }
        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            modInstance.connectedClients.remove(conn);
            System.out.println("WebSocket connection closed: " + conn.getRemoteSocketAddress() + " Code: " + code + " Reason: " + reason);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            // Likely don't need to handle incoming messages for this use case
            System.out.println("Received message from " + conn.getRemoteSocketAddress() + ": " + message);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            System.err.println("WebSocket error for connection " + (conn != null ? conn.getRemoteSocketAddress() : "UNKNOWN") + ":");
            ex.printStackTrace();
            if (conn != null) {
                // Ensure client is removed if an error occurs that might not trigger onClose
                connectedClients.remove(conn);
            }
        }

        @Override
        public void onStart() {
            System.out.println("WebSocket server internal start successful.");
            // Set TCP_NODELAY for lower latency, often useful for real-time data
            setTcpNoDelay(true);
        }
    }

    private byte[] serializeJoinMessage(ServerPlayerEntity player) throws Exception { // Or handle exceptions internally
        String playerName = player.getGameProfile().getName();
        byte[] nameBytes = playerName.getBytes(StandardCharsets.UTF_8);
        // Use the existing helper method
        Set<String> advancements = getPlayerAdvancements(player);
        List<byte[]> advancementBytesList = new ArrayList<>();
        int advancementsTotalBytes = 0;
        for (String adv : advancements) {
            byte[] advBytes = adv.getBytes(StandardCharsets.UTF_8);
            // Consider logging skipped advancements
            if (advBytes.length > 255) continue;
            advancementBytesList.add(advBytes);
            advancementsTotalBytes += (1 + advBytes.length);
        }

        int capacity = 1 // Type
                + 1 + nameBytes.length // Name
                + 8 + 8 + 8 // Coords (getX/Y/Z)
                + 4 // Health
                + 4 // XP
                + 2 // Advancement count
                + advancementsTotalBytes; // Advancements data

        ByteBuffer buffer = ByteBuffer.allocate(capacity);
        buffer.put(MSG_TYPE_JOIN);
        buffer.put((byte) nameBytes.length);
        buffer.put(nameBytes);
        buffer.putDouble(player.getX());
        buffer.putDouble(player.getY());
        buffer.putDouble(player.getZ());
        buffer.putFloat(player.getHealth());
        buffer.putInt(player.experienceLevel);
        buffer.putShort((short) advancementBytesList.size());
        for (byte[] advBytes : advancementBytesList) {
            buffer.put((byte) advBytes.length);
            buffer.put(advBytes);
        }
        return buffer.array();
    }


}
