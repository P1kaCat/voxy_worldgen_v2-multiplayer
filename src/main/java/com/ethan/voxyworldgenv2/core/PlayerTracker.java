package com.ethan.voxyworldgenv2.core;

import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerTracker {
    private static final PlayerTracker INSTANCE = new PlayerTracker();
    private final Set<ServerPlayer> players;
    
    private PlayerTracker() {
        this.players = ConcurrentHashMap.newKeySet();
    }
    
    public static PlayerTracker getInstance() {
        return INSTANCE;
    }
    
    public void addPlayer(ServerPlayer player) {
        players.add(player);
    }
    
    public void removePlayer(ServerPlayer player) {
        players.remove(player);
    }
    
    public void clear() {
        players.clear();
    }
    
    public Collection<ServerPlayer> getPlayers() {
        return Collections.unmodifiableCollection(players);
    }
    
    public int getPlayerCount() {
        return players.size();
    }
}
