package dev.weapons;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * BetterModel bridge (BetterModel 3.x, Paper). Plays an animation from a BetterModel "player model" (a Blockbench model in
 * plugins/BetterModel/players/) on the player, so it looks like the player is reloading, spinning the halberd, doing the
 * uppercut... Everything is called through reflection, so this plugin builds and runs without BetterModel; if BetterModel,
 * the model or the animation is missing, nothing happens and the built-in ghost-weapon animation is used instead.
 *
 * The animation name is {@code <weapon id>_<event>}, e.g. halberd_of_despair_spin, excalibur_uppercut, ak47_reload.
 */
final class ModelAnim {

    private static boolean tried;
    private static boolean ok;
    private static Class<?> cEntityTracker;
    private static Class<?> cTrackerModifier;
    private static Class<?> cAnimModifier;
    private static Class<?> cType;
    private static Class<?> cHide;
    private static Method mLimb;
    private static Method mAnimation;
    private static Method mAdapt;
    private static Method mGetOrCreate;
    private static Method mAnimate;
    private static Method mClose;
    private static Method mHideOption;
    private static Constructor<?> ctorModifier;
    private static Object trackerDefault;
    private static Object hideDefault;
    private static Object hideFalse;

    private static final Map<UUID, Object> active = new HashMap<>();

    private ModelAnim() {
    }

    private static boolean init(WeaponsPlugin plugin) {
        if (tried) {
            return ok;
        }
        Plugin bm = Bukkit.getPluginManager().getPlugin("BetterModel");
        if (bm == null || !bm.isEnabled()) {
            return false; // maybe it loads later: try again next time
        }
        tried = true;
        try {
            ClassLoader cl = bm.getClass().getClassLoader();
            Class<?> cBetterModel = Class.forName("kr.toxicity.model.api.BetterModel", true, cl);
            Class<?> cRenderer = Class.forName("kr.toxicity.model.api.data.renderer.ModelRenderer", true, cl);
            Class<?> cBlueprint = Class.forName("kr.toxicity.model.api.data.blueprint.BlueprintAnimation", true, cl);
            Class<?> cPlatformEntity = Class.forName("kr.toxicity.model.api.platform.PlatformEntity", true, cl);
            cEntityTracker = Class.forName("kr.toxicity.model.api.tracker.EntityTracker", true, cl);
            cTrackerModifier = Class.forName("kr.toxicity.model.api.tracker.TrackerModifier", true, cl);
            cAnimModifier = Class.forName("kr.toxicity.model.api.animation.AnimationModifier", true, cl);
            cType = Class.forName("kr.toxicity.model.api.animation.AnimationIterator$Type", true, cl);
            cHide = Class.forName("kr.toxicity.model.api.tracker.EntityHideOption", true, cl);
            Class<?> cAdapter = Class.forName("kr.toxicity.model.api.bukkit.platform.BukkitAdapter", true, cl);

            mLimb = cBetterModel.getMethod("limb", String.class);
            mAnimation = cRenderer.getMethod("animation", String.class);
            mAdapt = cAdapter.getMethod("adapt", Player.class);
            mGetOrCreate = cRenderer.getMethod("getOrCreate", cPlatformEntity, cTrackerModifier, Consumer.class);
            mAnimate = cEntityTracker.getMethod("animate", cBlueprint, cAnimModifier, Runnable.class);
            mClose = cEntityTracker.getMethod("close");
            mHideOption = cEntityTracker.getMethod("hideOption", cHide);
            ctorModifier = cAnimModifier.getConstructor(int.class, int.class, cType);
            trackerDefault = cTrackerModifier.getField("DEFAULT").get(null);
            hideDefault = cHide.getField("DEFAULT").get(null);
            hideFalse = cHide.getField("FALSE").get(null);
            ok = true;
            plugin.getLogger().info("BetterModel found: weapon animations will use its player models.");
        } catch (Throwable t) {
            ok = false;
            plugin.getLogger().warning("BetterModel is installed but its API did not match (" + t + "). Using the built-in animations.");
        }
        return ok;
    }

    private static String modelFor(WeaponsPlugin plugin, WeaponType type) {
        String per = plugin.getConfig().getString("animations.bettermodel.models." + type.id());
        if (per != null && !per.isBlank()) {
            return per;
        }
        return plugin.getConfig().getString("animations.bettermodel.model", "weapons");
    }

    private static String animName(WeaponType type, String event) {
        return type.id() + "_" + (event.startsWith("ability_") ? event.substring(8) : event);
    }

    /** Does the player model have an animation for this event? (Used to skip the built-in ghost animation.) */
    static boolean has(WeaponsPlugin plugin, WeaponType type, String event) {
        if (!plugin.getConfig().getBoolean("animations.bettermodel.enabled", true) || !init(plugin)) {
            return false;
        }
        try {
            Object renderer = ((Optional<?>) mLimb.invoke(null, modelFor(plugin, type))).orElse(null);
            return renderer != null && ((Optional<?>) mAnimation.invoke(renderer, animName(type, event))).isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Plays {@code <weapon id>_<event>} on the player. Returns false if it could not (no BetterModel, model or animation). */
    static boolean play(WeaponsPlugin plugin, Player p, WeaponType type, String event) {
        if (type == null || !plugin.getConfig().getBoolean("animations.bettermodel.enabled", true) || !init(plugin)) {
            return false;
        }
        try {
            Object renderer = ((Optional<?>) mLimb.invoke(null, modelFor(plugin, type))).orElse(null);
            if (renderer == null) {
                return false;
            }
            Optional<?> anim = (Optional<?>) mAnimation.invoke(renderer, animName(type, event));
            if (anim.isEmpty()) {
                return false;
            }
            stop(p);
            boolean hide = plugin.getConfig().getBoolean("animations.bettermodel.hide-player", true);
            Object platformPlayer = mAdapt.invoke(null, p);
            Consumer<Object> setup = t -> {
                try {
                    mHideOption.invoke(t, hide ? hideDefault : hideFalse);
                } catch (Throwable ignored) {
                    // keep the default
                }
            };
            Object tracker = mGetOrCreate.invoke(renderer, platformPlayer, trackerDefault, setup);
            String loop = plugin.getConfig().getString("animations.bettermodel.loop." + animName(type, event), "PLAY_ONCE");
            Object loopType = cType.getMethod("valueOf", String.class).invoke(null, loop.toUpperCase(java.util.Locale.ROOT));
            Object modifier = ctorModifier.newInstance(0, 0, loopType);
            final UUID id = p.getUniqueId();
            final Object tr = tracker;
            Runnable done = () -> {
                if (active.get(id) == tr) {
                    active.remove(id);
                }
                close(tr);
            };
            active.put(id, tracker);
            mAnimate.invoke(tracker, anim.get(), modifier, done);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("BetterModel animation failed (" + animName(type, event) + "): " + t);
            return false;
        }
    }

    /** Ends the player's current animation (reload cancelled, death, quit...). */
    static void stop(Player p) {
        Object tr = active.remove(p.getUniqueId());
        if (tr != null) {
            close(tr);
        }
    }

    private static void close(Object tracker) {
        try {
            mClose.invoke(tracker);
        } catch (Throwable ignored) {
            // already closed
        }
    }

    static void shutdown() {
        for (Object tr : active.values()) {
            close(tr);
        }
        active.clear();
    }
}
