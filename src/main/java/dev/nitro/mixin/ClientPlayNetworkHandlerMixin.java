package dev.nitro.mixin;

import dev.nitro.modules.BlockEntityEsp;
import dev.nitro.modules.WeatherNotifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only listeners on the server's packets for Block Entity ESP and Weather Notifier. Packet handlers run once on the
 * network thread (which only re-queues them) and once on the main thread, so every hook returns early off the main thread.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onChunkData", at = @At("HEAD"))
    private void nitro$chunkData(ChunkDataS2CPacket packet, CallbackInfo ci) {
        if (MinecraftClient.getInstance().isOnThread()) BlockEntityEsp.onChunkData(packet);
    }

    @Inject(method = "onBlockEntityUpdate", at = @At("HEAD"))
    private void nitro$blockEntityUpdate(BlockEntityUpdateS2CPacket packet, CallbackInfo ci) {
        if (MinecraftClient.getInstance().isOnThread()) BlockEntityEsp.onBlockEntityUpdate(packet);
    }

    @Inject(method = "onBlockUpdate", at = @At("HEAD"))
    private void nitro$blockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
        if (MinecraftClient.getInstance().isOnThread()) BlockEntityEsp.onBlockUpdate(packet.getPos(), packet.getState());
    }

    @Inject(method = "onChunkDeltaUpdate", at = @At("HEAD"))
    private void nitro$chunkDelta(ChunkDeltaUpdateS2CPacket packet, CallbackInfo ci) {
        if (MinecraftClient.getInstance().isOnThread()) BlockEntityEsp.onChunkDelta(packet);
    }

    @Inject(method = "onGameStateChange", at = @At("HEAD"))
    private void nitro$gameState(GameStateChangeS2CPacket packet, CallbackInfo ci) {
        if (MinecraftClient.getInstance().isOnThread()) WeatherNotifier.onGameState(packet);
    }
}
