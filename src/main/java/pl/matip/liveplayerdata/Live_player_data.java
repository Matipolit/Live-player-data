package pl.matip.liveplayerdata;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
// Import the event for player joining
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
// Import needed for the JOIN event handler signature
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.fabricmc.fabric.api.networking.v1.PacketSender;


import java.io.Console;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;

// Bitmask definitions
// bit 0: coordinates changed
// bit 1: health changed
// bit 2: xp changed
// bit 3: achievements changed (stub)
public class Live_player_data implements DedicatedServerModInitializer {

    // Holds data for each player by name
    private HashMap<String, PlayerData> playerDataMap;
    private int tickDebouncer;

    // UDP connection details (modify as needed)
    private DatagramSocket udpSocket;
    private InetAddress remoteAddress;
    private int remotePort = 9999;  // for example, choose a port your Rust program listens on

    // Define the "all changed" mask for clarity
    private static final byte ALL_CHANGED_MASK = (byte) 0xF; // 0b00001111
    private static final byte LEAVE_MASK = (byte) 0x20; // Bit 5

    @Override
    public void onInitializeServer() {
        System.out.println("Initializing Live Player data ...");
        playerDataMap = new HashMap<>();
        tickDebouncer = 0;
        try {
            udpSocket = new DatagramSocket();
            remoteAddress = InetAddress.getByName("127.0.0.1"); // or the IP of your Rust program
        } catch (Exception e) {
            System.err.println("Failed to initialize UDP socket:");
            e.printStackTrace();
        }

        // Register server tick event handler (for periodic updates)
        ServerTickEvents.END_SERVER_TICK.register(this::handleServerTick);

        // Register player join event handler (for initial data dump)
        ServerPlayConnectionEvents.JOIN.register(this::handlePlayerJoin);

        // Optional: Register player leave event handler to clean up map
        ServerPlayConnectionEvents.DISCONNECT.register(this::handlePlayerLeave);

        System.out.println("Live Player data initialized and listening for events.");
    }

    // --- Event Handlers ---

    private void handlePlayerJoin(ServerPlayNetworkHandler handler, PacketSender sender, MinecraftServer server) {
        ServerPlayerEntity player = handler.player; // Get the player who joined
        String playerName = player.getGameProfile().getName();
        System.out.println("Player joined: " + playerName + ". Sending initial data.");

        // Gather current data
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float health = player.getHealth();
        int xp = player.experienceLevel;
        String achievements = "dummy_on_join"; // Use a relevant initial value or fetch real ones

        PlayerData currentData = new PlayerData(x, y, z, health, xp, achievements);

        // Store the initial data
        playerDataMap.put(playerName, currentData);

        // Send UDP update with ALL fields marked as changed
        sendUdpUpdate(playerName, ALL_CHANGED_MASK, currentData);
    }

    private void handlePlayerLeave(ServerPlayNetworkHandler handler, MinecraftServer server) {
        String playerName = handler.player.getGameProfile().getName();
        System.out.println("Player left: " + playerName + ". Removing data and sending leave notification.");
        // Remove player data from our map
        playerDataMap.remove(playerName);
        // Send a specific leave notification packet
        sendUdpLeaveNotification(playerName);
    }


    private void handleServerTick(MinecraftServer server) {
        // Debounce logic remains the same
        if (tickDebouncer < 30) {
            tickDebouncer++;
            return;
        }
        tickDebouncer = 0;

        // Iterate over all online players for periodic checks
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String playerName = player.getGameProfile().getName();

            // Gather current data
            double x = player.getX();
            double y = player.getY();
            double z = player.getZ();
            float health = player.getHealth();
            int xp = player.experienceLevel;
            String achievements = "dummy_tick"; // Fetch real achievements/advancements here

            PlayerData currentData = new PlayerData(x, y, z, health, xp, achievements);

            // Check previously stored data
            PlayerData oldData = playerDataMap.get(playerName);
            byte changeMask = 0;

            // If oldData is null here, it means the player joined *between*
            // the JOIN event and this tick, or the JOIN event failed.
            // The JOIN handler should ideally prevent this, but we can be safe.
            if (oldData == null) {
                System.err.println("Warning: Player " + playerName + " found in tick but not in map. Sending full update.");
                changeMask = ALL_CHANGED_MASK; // Treat as all changed if missing
            } else {
                // Compare fields like before
                if (!oldData.coordsEquals(currentData)) {
                    changeMask |= 0x1; // bit 0
                }
                if (oldData.health != currentData.health) {
                    changeMask |= 0x2; // bit 1
                }
                if (oldData.xp != currentData.xp) {
                    changeMask |= 0x4; // bit 2
                }
                if (!oldData.achievements.equals(currentData.achievements)) {
                    changeMask |= 0x8; // bit 3
                }
            }

            // If any data has changed (or if it was a new player missed by JOIN),
            // update storage and send UDP update
            if (changeMask != 0) {
                playerDataMap.put(playerName, currentData); // Update map
                sendUdpUpdate(playerName, changeMask, currentData);
            }
        }
    }

    private void sendUdpLeaveNotification(String playerName) {
        if (udpSocket == null || remoteAddress == null) {
            System.err.println("UDP Socket not initialized. Cannot send leave notification for " + playerName);
            return;
        }
        try {
            byte[] playerNameBytes = playerName.getBytes(StandardCharsets.UTF_8);

            // Capacity: Magic (2) + NameLen (1) + NameBytes + Mask (1)
            // NO data fields are included for a leave message.
            int capacity = 2 + 1 + playerNameBytes.length + 1;

            ByteBuffer buffer = ByteBuffer.allocate(capacity);
            buffer.put((byte) 0xCA);
            buffer.put((byte) 0xFE);
            buffer.put((byte) playerNameBytes.length);
            buffer.put(playerNameBytes);
            buffer.put(LEAVE_MASK); // Put the specific leave mask

            byte[] packetData = buffer.array();
            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, remoteAddress, remotePort);
            udpSocket.send(packet);
            // System.out.println("Sent UDP leave notification for " + playerName);

        } catch (Exception e) {
            System.err.println("Failed to send UDP leave notification for " + playerName + ":");
            e.printStackTrace();
        }
    }

    private void sendUdpUpdate(String playerName, byte changeMask, PlayerData data) {
        // Make sure socket is initialized before trying to send
        if (udpSocket == null || remoteAddress == null) {
            System.err.println("UDP Socket not initialized. Cannot send update for " + playerName);
            return;
        }
        try {
            byte[] playerNameBytes = playerName.getBytes(StandardCharsets.UTF_8);
            byte[] achievementsBytes = data.achievements.getBytes(StandardCharsets.UTF_8);

            int capacity = 2 + 1 + playerNameBytes.length + 1; // Magic + nameLen + name + changeMask
            if ((changeMask & 0x1) != 0) capacity += 24; // Coords
            if ((changeMask & 0x2) != 0) capacity += 4;  // Health
            if ((changeMask & 0x4) != 0) capacity += 4;  // XP
            if ((changeMask & 0x8) != 0) capacity += 1 + achievementsBytes.length; // Achievements Len + Data

            ByteBuffer buffer = ByteBuffer.allocate(capacity);
            buffer.put((byte) 0xCA);
            buffer.put((byte) 0xFE);
            buffer.put((byte) playerNameBytes.length);
            buffer.put(playerNameBytes);
            buffer.put(changeMask);

            if ((changeMask & 0x1) != 0) {
                buffer.putDouble(data.x);
                buffer.putDouble(data.y);
                buffer.putDouble(data.z);
            }
            if ((changeMask & 0x2) != 0) {
                buffer.putFloat(data.health);
            }
            if ((changeMask & 0x4) != 0) {
                buffer.putInt(data.xp);
            }
            if ((changeMask & 0x8) != 0) {
                buffer.put((byte) achievementsBytes.length);
                buffer.put(achievementsBytes);
            }

            byte[] packetData = buffer.array();
            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, remoteAddress, remotePort);
            udpSocket.send(packet);
            // Optional: Log the send
            // System.out.println("Sent UDP update for " + playerName + " mask: " + String.format("0x%02X", changeMask));

        } catch (Exception e) {
            System.err.println("Failed to send UDP update for " + playerName + ":");
            e.printStackTrace();
        }
    }

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
}
