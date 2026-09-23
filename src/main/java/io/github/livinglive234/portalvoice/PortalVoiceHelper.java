package io.github.livinglive234.portalvoice;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.List;

/**
 * Portal-aware distance math, mirroring how Immersive Portals handles cross-portal
 * sound: sound travels from the speaker to the nearest point on the portal, pops out
 * at the transformed position on the other side, then travels to the listener.
 *
 * <p><b>Threading:</b> callers MUST invoke this on the Minecraft server thread. The
 * portal lookup iterates the level's entity storage.</p>
 */
public final class PortalVoiceHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(PortalVoiceCompat.MOD_ID);

    private PortalVoiceHelper() {
    }

    /**
     * A viable voice path from speaker to listener through one portal.
     *
     * @param soundPos where to place the positional audio packet, in the LISTENER's
     *                 dimension. Not the raw portal exit: it is pushed out along the
     *                 listener-&gt;portal-exit ray until its distance from the listener
     *                 equals the true path length, so the client's normal falloff gives
     *                 the correct loudness while panning still points at the portal.
     * @param distance total path length: speaker-&gt;portal + portal exit-&gt;listener
     */
    public record PortalRoute(Vec3 soundPos, double distance) {
    }

    /**
     * Portals near {@code sender} that voice can pass through. Called once per voice
     * packet (not once per listener), since the result is the same for every listener.
     * Must be called on the server thread.
     */
    public static List<Portal> voicePortalsNear(ServerPlayer sender, double maxRange) {
        List<Portal> portals = new ArrayList<>();
        Vec3 senderPos = sender.position();
        try {
            // Covers global portals as well as portal entities.
            IPMcHelper.foreachNearbyPortals(sender.level(), senderPos, (int) Math.ceil(maxRange), portal -> {
                if (portal.isPortalValid() && portal.isVisible() && portal.isInFrontOfPortal(senderPos)) {
                    portals.add(portal);
                }
            });
        } catch (Exception e) {
            LOGGER.debug("Portal lookup failed", e);
            portals.clear();
        }
        return portals;
    }

    /**
     * Shortest route from the sender through any of {@code portals} to
     * {@code receiver}, or null if nothing is within {@code maxRange}.
     */
    @Nullable
    public static PortalRoute findBestRoute(List<Portal> portals, Vec3 senderPos,
                                            ServerPlayer receiver, double maxRange) {
        ResourceKey<Level> receiverDim = receiver.level().dimension();
        Vec3 receiverPos = receiver.position();
        PortalRoute best = null;

        for (Portal portal : portals) {
            try {
                if (!portal.getDestDim().equals(receiverDim)) {
                    continue;
                }

                Vec3 nearestOnPortal = portal.getNearestPointInPortal(senderPos);
                double distToPortal = senderPos.distanceTo(nearestOnPortal);
                if (distToPortal > maxRange) {
                    continue;
                }

                Vec3 exitPos = portal.transformPoint(nearestOnPortal);
                Vec3 toExit = exitPos.subtract(receiverPos);
                double distExitToReceiver = toExit.length();
                double total = distToPortal + distExitToReceiver;
                if (total > maxRange || (best != null && total >= best.distance())) {
                    continue;
                }

                // Degenerate case: listener is exactly at the exit, so any direction works.
                Vec3 dir = distExitToReceiver > 1e-3
                        ? toExit.scale(1.0 / distExitToReceiver)
                        : new Vec3(0, 0, 1);
                best = new PortalRoute(receiverPos.add(dir.scale(total)), total);
            } catch (Exception e) {
                // One misbehaving portal shouldn't take down voice for everyone.
                LOGGER.debug("Skipping portal due to error", e);
            }
        }
        return best;
    }
}
