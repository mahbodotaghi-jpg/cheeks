package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import net.minecraft.item.Item;

/**
 * Animations: smoother, more consistent first-person hand and item motion. It reshapes the equip progress the game uses
 * for the held item (the raise/lower motion when you switch items, and the small dip after every attack while the cooldown
 * recharges). Purely visual: attack cooldown, damage and item use are not affected. Swing styles live in Swing Animation.
 * The work is done by HeldItemRendererMixin, which calls {@link #equip(float)}.
 */
public final class Animations extends Module {
    public enum Ease {
        OFF("Off (vanilla)"), SMOOTH("Smooth"), SMOOTHER("Smoother"), EASE_OUT("Ease out");
        private final String label;
        Ease(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public static Animations INSTANCE;

    private final Setting.Choice<Ease> ease = new Setting.Choice<>("Equip easing", Ease.SMOOTH, Ease.class);
    private final Setting.Num depth = new Setting.Num("Switch depth (%)", 70, 0, 100, 5);
    private final Setting.Num dip = new Setting.Num("Attack dip (%)", 35, 0, 100, 5);

    private Item lastMain, lastOff;
    private int lastSlot = -1;
    private int stable; // ticks since the held items / selected slot last changed

    public Animations() {
        super("Animations", "Smooths the first-person item raise/lower and softens the dip after each attack.", Category.RENDER);
        add(ease, depth, dip);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        stable = 0;
    }

    @Override
    public void onTick() {
        if (mc.player == null) return;
        Item m = mc.player.getMainHandStack().getItem();
        Item o = mc.player.getOffHandStack().getItem();
        int slot = mc.player.getInventory().selectedSlot;
        if (m != lastMain || o != lastOff || slot != lastSlot) {
            stable = 0;
            lastMain = m;
            lastOff = o;
            lastSlot = slot;
        } else if (stable < 1000) {
            stable++;
        }
    }

    /**
     * Equip progress as the renderer sees it: 0 = item fully raised, 1 = fully lowered. While you are switching items the
     * curve is eased and scaled by "Switch depth"; once the held items have been stable for a while the only thing left that
     * lowers the item is the attack cooldown, which is scaled by "Attack dip".
     */
    public static float equip(float p) {
        Animations s = INSTANCE;
        if (s == null || !s.enabled) return p;
        float t = Math.max(0f, Math.min(1f, p));
        float e = switch (s.ease.value) {
            case OFF -> t;
            case SMOOTH -> t * t * (3f - 2f * t);
            case SMOOTHER -> t * t * t * (t * (t * 6f - 15f) + 10f);
            case EASE_OUT -> 1f - (1f - t) * (1f - t) * (1f - t);
        };
        float k = (s.stable >= 10 ? s.dip.f() : s.depth.f()) / 100f;
        return e * k;
    }
}
