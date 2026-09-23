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
 * <p><b>Threading:</b> {@link #voicePortalsNear} MUST run on the Minecraft server
 * thread (it iterates entity storage). {@link #findBestRoute} is only geometry on
 * already-loaded portals, so it can run on Simple Voice Chat's packet thread.</p>
 */
public final class PortalVoiceHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(PortalVoiceCompat.MOD_ID);

    private PortalVoiceHelper() {
    }

    /**
     * A viable voice path from speaker to listener through one portal.
     *
     * @param soundPos where to place the positional audio packet, in the LISTENER's
     *                 dimension. It lies on the ray from the listener toward where the
     *                 speaker appears through the portal, at a distance equal to the
     *                 true path length, so the client's normal falloff gives the
     *                 correct loudness while panning matches what the listener sees.
     * @param distance total path length: speaker-&gt;portal + portal exit-&gt;listener
     */
    public record PortalRoute(Vec3 soundPos, double distance) {
    }

    /**
     * Valid, visible portals within {@code radius} of {@code sender}. Must be called on
     * the server thread; the result is immutable state that is safe to read elsewhere.
     */
    public static List<Portal> voicePortalsNear(ServerPlayer sender, double radius) {
        List<Portal> portals = new ArrayList<>();
        try {
            // Covers global portals as well as portal entities.
            IPMcHelper.foreachNearbyPortals(sender.level(), sender.position(), (int) Math.ceil(radius), portal -> {
                if (portal.isPortalValid() && portal.isVisible()) {
                    portals.add(portal);
                }
            });
        } catch (Exception e) {
            LOGGER.debug("Portal lookup failed", e);
            portals.clear();
        }
        return List.copyOf(portals);
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
                if (!portal.getDestDim().equals(receiverDim) || !portal.isInFrontOfPortal(senderPos)) {
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

                // Aim at where the speaker appears through the portal (the spot the
                // listener sees them standing), so the voice comes from that direction.
                Vec3 toApparent = portal.transformPoint(senderPos).subtract(receiverPos);
                Vec3 dir = toApparent.lengthSqr() > 1e-6 ? toApparent.normalize() : new Vec3(0, 0, 1);
                best = new PortalRoute(receiverPos.add(dir.scale(total)), total);
            } catch (Exception e) {
                // One misbehaving portal shouldn't take down voice for everyone.
                LOGGER.debug("Skipping portal due to error", e);
            }
        }
        return best;
    }
}
