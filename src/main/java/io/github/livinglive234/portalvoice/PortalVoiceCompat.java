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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.List;
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
 * <p>The packet is positioned along the listener-&gt;portal-exit ray at the true path
 * distance, so volume is correct and panning points at the portal. No client mod is
 * needed. The trade-off is that direction always points at the portal, not at the
 * speaker.</p>
 *
 * <p><b>Threading:</b> Simple Voice Chat fires mic events on its own packet thread.
 * The world and player list are only touched after hopping to the server thread.</p>
 */
public class PortalVoiceCompat implements ModInitializer, VoicechatPlugin {
    public static final String MOD_ID = "portal-voice-compat";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile MinecraftServer server;
    private volatile VoicechatServerApi voicechatApi;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            LOGGER.info("Server started, portal-aware voice active");
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
    }

    @Override
    public String getPluginId() {
        return MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatServerApi serverApi) {
            this.voicechatApi = serverApi;
            LOGGER.info("Hooked into Simple Voice Chat server API");
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    /**
     * Runs on Simple Voice Chat's packet thread for every mic packet. Cheap filters
     * happen here; everything that touches the world is deferred to the server thread.
     */
    private void onMicrophonePacket(MicrophonePacketEvent event) {
        VoicechatServerApi api = this.voicechatApi;
        MinecraftServer srv = server;
        if (api == null || srv == null) {
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

        UUID senderUuid = senderConn.getPlayer().getUuid();
        srv.execute(() -> routeThroughPortals(api, srv, senderUuid, micPacket));
    }

    private void routeThroughPortals(VoicechatServerApi api, MinecraftServer srv,
                                     UUID senderUuid, MicrophonePacket micPacket) {
        ServerPlayer sender = srv.getPlayerList().getPlayer(senderUuid);
        if (sender == null || sender.isSpectator()) {
            return;
        }

        double voiceDistance = api.getVoiceChatDistance();
        double maxRange = micPacket.isWhispering()
                ? resolveWhisperDistance(api, voiceDistance)
                : voiceDistance;

        // Most speakers are nowhere near a portal, so bail out before looking at listeners.
        List<Portal> portals = PortalVoiceHelper.voicePortalsNear(sender, maxRange);
        if (portals.isEmpty()) {
            return;
        }

        Vec3 senderPos = sender.position();
        for (ServerPlayer receiver : srv.getPlayerList().getPlayers()) {
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
                    PortalVoiceHelper.findBestRoute(portals, senderPos, receiver, maxRange);
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

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("{} -> {} via portal, path {} blocks", sender.getGameProfile().getName(),
                            receiver.getGameProfile().getName(), String.format("%.1f", route.distance()));
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to send portal voice to {}", receiver.getGameProfile().getName(), e);
            }
        }
    }

    /** Mirrors vanilla: {@code whisper_distance} below 0 means "use the voice distance". */
    private double resolveWhisperDistance(VoicechatServerApi api, double voiceDistance) {
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
