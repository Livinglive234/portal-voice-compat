package io.github.livinglive234.portalvoice;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.packets.LocationalSoundPacket;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Makes Simple Voice Chat proximity voice audible through Immersive Portals portals.
 *
 * <p>Simple Voice Chat only delivers proximity voice within one dimension, and only
 * by straight-line distance. This plugin observes each microphone packet without
 * cancelling it (so vanilla routing is untouched) and additionally sends a
 * {@link LocationalSoundPacket} to listeners who are within range <em>through a
 * portal</em> but not directly. That includes listeners in another dimension and
 * listeners in the same dimension whose direct distance is out of range.</p>
 *
 * <p>The packet is positioned along the ray from the listener toward where the speaker
 * appears through the portal, at the true path distance, so volume follows the path
 * and panning matches what the listener sees. No client mod is needed.</p>
 *
 * <p><b>Threading:</b> Simple Voice Chat fires mic events on its own packet thread, and
 * packets are sent from there directly. Queueing each one onto the server thread would
 * deliver them in tick-sized bursts, which sounds like random pops. Instead the server
 * thread refreshes an immutable {@link Snapshot} of players and nearby portals a couple
 * of times a second, and the voice thread only reads it.</p>
 */
public class PortalVoiceCompat implements ModInitializer, VoicechatPlugin {
    public static final String MOD_ID = "portal-voice-compat";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int REFRESH_INTERVAL_TICKS = 10;
    // Speakers may walk a few blocks between refreshes, so search a bit past voice range.
    private static final double PORTAL_SEARCH_MARGIN = 8;

    /** A player standing near at least one portal, with those portals. */
    private record Speaker(ServerPlayer player, List<Portal> portals) {
    }

    private record Snapshot(List<ServerPlayer> players, Map<UUID, Speaker> speakersNearPortals) {
        static final Snapshot EMPTY = new Snapshot(List.of(), Map.of());
    }

    // Static because Fabric creates a separate instance per entrypoint (main, voicechat)
    // and the tick handler and the voice handler live on different ones.
    private static volatile VoicechatServerApi voicechatApi;
    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> LOGGER.info("Server started, portal-aware voice active"));
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> snapshot = Snapshot.EMPTY);
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTickCount() % REFRESH_INTERVAL_TICKS == 0) {
                refreshSnapshot(s);
            }
        });
    }

    /** Server thread: capture players and the portals near each of them. */
    private static void refreshSnapshot(MinecraftServer srv) {
        VoicechatServerApi api = voicechatApi;
        if (api == null) {
            return;
        }
        double radius = api.getVoiceChatDistance() + PORTAL_SEARCH_MARGIN;
        List<ServerPlayer> players = List.copyOf(srv.getPlayerList().getPlayers());
        Map<UUID, Speaker> speakers = new HashMap<>();
        for (ServerPlayer player : players) {
            List<Portal> portals = PortalVoiceHelper.voicePortalsNear(player, radius);
            if (!portals.isEmpty()) {
                speakers.put(player.getUUID(), new Speaker(player, portals));
            }
        }
        snapshot = new Snapshot(players, Map.copyOf(speakers));
    }

    @Override
    public String getPluginId() {
        return MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatServerApi serverApi) {
            voicechatApi = serverApi;
            LOGGER.info("Hooked into Simple Voice Chat server API");
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    /**
     * Runs on Simple Voice Chat's packet thread for every mic packet, and sends portal
     * voice from there. Only reads the snapshot and portal geometry, never world state.
     */
    private void onMicrophonePacket(MicrophonePacketEvent event) {
        VoicechatServerApi api = voicechatApi;
        if (api == null) {
            return;
        }

        // Respect other plugins that silenced this packet.
        if (event.isCancelled()) {
            return;
        }

        VoicechatConnection senderConn = event.getSenderConnection();
        if (senderConn == null || !senderConn.isConnected() || senderConn.isDisabled()) {
            return;
        }

        // Group chat is private: vanilla never delivers it as proximity voice.
        if (senderConn.isInGroup()) {
            return;
        }

        if (!(event.getPacket() instanceof MicrophonePacket micPacket)) {
            return;
        }

        // Most speakers are nowhere near a portal, so this is usually the whole cost.
        Snapshot snap = snapshot;
        Speaker speaker = snap.speakersNearPortals().get(senderConn.getPlayer().getUuid());
        if (speaker == null || speaker.player().isSpectator()) {
            return;
        }

        routeThroughPortals(api, snap, speaker, micPacket);
    }

    private static void routeThroughPortals(VoicechatServerApi api, Snapshot snap, Speaker speaker,
                                     MicrophonePacket micPacket) {
        ServerPlayer sender = speaker.player();
        double voiceDistance = api.getVoiceChatDistance();
        double maxRange = micPacket.isWhispering()
                ? resolveWhisperDistance(api, voiceDistance)
                : voiceDistance;

        Vec3 senderPos = sender.position();
        for (ServerPlayer receiver : snap.players()) {
            if (receiver == sender) {
                continue;
            }

            // Vanilla already delivers to same-dimension listeners within direct range.
            if (receiver.level() == sender.level()
                    && receiver.position().distanceTo(senderPos) <= maxRange) {
                continue;
            }

            VoicechatConnection receiverConn = api.getConnectionOf(receiver.getUUID());
            if (receiverConn == null || !receiverConn.isConnected() || receiverConn.isDisabled()) {
                continue;
            }

            PortalVoiceHelper.PortalRoute route =
                    PortalVoiceHelper.findBestRoute(speaker.portals(), senderPos, receiver, maxRange);
            if (route == null) {
                continue;
            }

            try {
                // Built from the mic packet so sender, sequence number, channel and audio
                // carry over. Distance is set explicitly: the default conversion would use
                // the normal voice distance even for whispers.
                Vec3 pos = route.soundPos();
                Position position = api.createPosition(pos.x, pos.y, pos.z);
                LocationalSoundPacket soundPacket = micPacket.locationalSoundPacketBuilder()
                        .position(position)
                        .distance((float) maxRange)
                        .build();
                api.sendLocationalSoundPacketTo(receiverConn, soundPacket);

                LOGGER.debug("{} -> {} via portal, path {} blocks", sender.getGameProfile().getName(),
                        receiver.getGameProfile().getName(), route.distance());
            } catch (Exception e) {
                LOGGER.debug("Failed to send portal voice to {}", receiver.getGameProfile().getName(), e);
            }
        }
    }

    /** Mirrors vanilla: {@code whisper_distance} below 0 means "use the voice distance". */
    private static double resolveWhisperDistance(VoicechatServerApi api, double voiceDistance) {
        try {
            if (api.getServerConfig().hasKey("whisper_distance")) {
                double whisperDistance = api.getServerConfig().getDouble("whisper_distance", voiceDistance);
                return whisperDistance < 0 ? voiceDistance : whisperDistance;
            }
        } catch (Exception e) {
            LOGGER.debug("Could not read whisper_distance config", e);
        }
        return voiceDistance;
    }
}
