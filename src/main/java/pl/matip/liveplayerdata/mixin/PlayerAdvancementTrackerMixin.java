package pl.matip.liveplayerdata.mixin;

import net.minecraft.advancement.AdvancementDisplay;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.advancement.PlayerAdvancementTracker; // Correct package

import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.matip.liveplayerdata.Live_player_data;

@Mixin(PlayerAdvancementTracker.class) // Target the correct class
public abstract class PlayerAdvancementTrackerMixin {

    @Shadow private ServerPlayerEntity owner;

    @Inject(method = "grantCriterion", at = @At("RETURN"))
    private void onGrantCriterionReturn(AdvancementEntry advancement, String criterionName, CallbackInfoReturnable<Boolean> cir) {
        PlayerAdvancementTracker tracker = (PlayerAdvancementTracker)(Object)this;
        AdvancementProgress progress = tracker.getProgress(advancement);

        // Simplified check: If it's done now, send update.
        // This might send duplicates if criteria are granted after completion.
        // A more robust check would involve comparing state before/after or tracking sent advancements.
        if (progress.isDone()) {
            String advancementId = advancement.id().toString();


            if (this.owner != null) {
                String playerName = this.owner.getGameProfile().getName();
                Live_player_data mainModInstance = Live_player_data.getInstance();
                if (mainModInstance != null) {
                    mainModInstance.sendSingleAchievementUpdate(playerName, advancementId);
                } else {
                    System.err.println("Could not get Live_player_data instance in Mixin!");
                }
            } else {
                System.err.println("Mixin could not access player owner!");
            }
        }
    }
}
